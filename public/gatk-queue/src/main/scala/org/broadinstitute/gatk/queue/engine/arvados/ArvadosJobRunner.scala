/*
* Queue support for dispatching jobs to Arvados.
*
* Copyright (c) 2015 Curoverse, Inc.
*
* Based on code Copyright (c) 2012 The Broad Institute
*
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
*
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.gatk.queue.engine.arvados

import org.broadinstitute.gatk.queue.QException
import org.broadinstitute.gatk.queue.util.{Logging,Retry}
import org.broadinstitute.gatk.queue.function.CommandLineFunction
import org.broadinstitute.gatk.queue.engine.{RunnerStatus, CommandLineJobRunner}
import java.util.{Date, Collections, HashMap, ArrayList, Map}
import org.arvados.sdk.Arvados
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import java.nio.file.{Files, Paths}
import com.google.api.client.http
import org.broadinstitute.gatk.utils.runtime.{ProcessSettings, OutputStreamSettings, ProcessController}
import java.io
import java.nio.charset.Charset
import java.io.PrintWriter
import scala.collection.JavaConversions._

/**
 * Runs jobs using Arvados.
 */
class ArvadosJobRunner(val arv: Arvados,
                       val jobs: scala.collection.mutable.Map[String, String],
                       val function: CommandLineFunction)
extends CommandLineJobRunner with Logging {

  /** Job Id of the currently executing job. */
  var jobUuid: String = _
  override def jobIdString = jobUuid
  var outfilePath: String = ""
  var outfileName: String = ""
  var workdir: String = ""

  def adjustOutput(cl: String) = {
    // Capture and adjust output path
    val rx  = """(.*)('-o' ')([^']+/\.queue/scatterGather/([^/]+/[^/]+)/([^']+))('.*)""".r
    val rx2 = """(.*)('-o' '|'-out' '|'OUTPUT=)([^']+/([^/']+))('.*)""".r

    cl match {
      case rx(head, param, path, dir, file, tail) => {
        outfilePath = path
        workdir = dir
        outfileName = file
        head + param + file + tail
      }
      case rx2(head, param, path, file, tail) => {
        outfilePath = path
        workdir = ""
        outfileName = file
        head + param + file + tail
      }
      case _ => cl
    }
  }

  def arvPut(src: String) = {
    // Need to shell out to arv-put to upload scatterdir
    val commandLine = Array("arv-put", "--no-progress", "--portable-data-hash", src)
    val stdoutSettings = new OutputStreamSettings
    val stderrSettings = new OutputStreamSettings

    val temp = java.io.File.createTempFile("PDH", ".tmp");

    stdoutSettings.setOutputFile(temp, true)

    val processSettings = new ProcessSettings(
      commandLine, false, function.commandDirectory, null,
      null, stdoutSettings, stderrSettings)

    val controller = ProcessController.getThreadLocal
    val exitStatus = controller.exec(processSettings).getExitValue
    if (exitStatus != 0) {
      updateStatus(RunnerStatus.FAILED)
      temp.delete()
      throw new QException("arv-put '" + src + "' failed")
    } else {
      val pdh = Files.readAllLines(Paths.get(temp.getPath()), Charset.defaultCharset()).get(0)
      temp.delete()
      pdh
    }
  }

  def adjustScatter(cl: String) = {
    // Capture and adjust scatter intervals
    val rx = """'-L' '([^']+/\.queue/scatterGather/[^/]+/[^/]+/)([^']+)'""".r
    rx.findFirstMatchIn(cl) match {
      case Some(m) => {
        // Need to shell out to arv-put to upload scatterdir
        (rx.replaceFirstIn(cl, "'-L' '$2'"), Some(arvPut(m.group(1))))
      }
      case None => (cl, None)
    }
  }

  def adjustTargetIntervals(cl: String) = {
    // Capture and adjust scatter intervals
    val rx = """'-targetIntervals' '([^']+/([^/']+))'""".r
    rx.findFirstMatchIn(cl) match {
      case Some(m) => {
        // Need to shell out to arv-put to upload scatterdir
        val pdh = arvPut(m.group(1))
        rx.replaceFirstIn(cl, "'-targetIntervals' '/keep/" + pdh + "/$2'")
      }
      case None => cl
    }
  }

  def adjustVCF(cl: String) = {
    val rx = """'-V' '([^']+)'""".r
    val sgrx =  """.*/\.queue/scatterGather/([^/]+/[^/]+)/(.+)""".r
    val keeprx = """/keep/.+""".r

    var cl2 = cl
    for (rx(file) <- rx findAllIn cl) {
      var found = "'-V' '" + file + "'"
      file match {
        case sgrx(work, fn) => {
          jobs.get(work) match {
            case Some(d) => {
              cl2 = cl2.replaceFirst(found, "'-V' '/keep/" + d + "/" + fn + "'")
            }
            case None => { }
          }
        }
        case keeprx() => { }
        case _ => {
          var target = Files.readSymbolicLink(Paths.get(file))
          cl2 = cl2.replaceFirst(found, "'-V' '" + target + "'")
        }
      }
    }
    cl2
  }

  def adjustMergeSamInput(cl: String) = {
    val rx = """'INPUT=[^']+/\.queue/scatterGather/([^/]+/[^/]+)/([^']+)'""".r
    var cl2 = cl
    for (rx(work, file) <- rx findAllIn cl) {
      jobs.get(work) match {
        case Some(d) => {
          cl2 = rx.replaceFirstIn(cl2, "'INPUT=/keep/" + d + "/" + file + "'")
        }
        case None => { }
      }
    }
    cl2
  }

  def start() {
    arv.synchronized {
      val queueJobUuid = System.getenv().get("JOB_UUID");
      var p = new HashMap[String, Object]()
      p.put("uuid", queueJobUuid)
      val jobRecord = arv.call("jobs", "get", p).asInstanceOf[Map[String,Object]]

      val body = new HashMap[String, Object]()
      body.put("script", "run-command")
      body.put("script_version", jobRecord.get("script_version"))
      body.put("repository", jobRecord.get("repository"))

      var cl = function.commandLine

      {
        // Adjust tmpdir
        val rx = """'-Djava.io.tmpdir=([^']+)'""".r
        cl = rx.replaceFirstIn(cl, "'-Djava.io.tmpdir=\\$(task.tmpdir)'")
      }
      {
        // Adjust tmpdir
        val rx = """'TMP_DIR=([^']+)'""".r
        cl = rx.replaceFirstIn(cl, "'TMP_DIR=\\$(task.tmpdir)'")
      }

      {
        // Adjust thread count
        val rx = """'(-nc?t)' '(\d+)'""".r
        cl = rx.replaceFirstIn(cl, "'$1' '\\$(node.cores)'")
      }

      val hap =        """.*'-T' '(HaplotypeCaller|RealignerTargetCreator)'.*""".r
      val indel =      """.*'-T' '(IndelRealigner)'.*""".r
      val cat =        """.*'org.broadinstitute.gatk.tools.(CatVariants)'.*""".r
      val mergesam =   """.*'picard.sam.(MergeSamFiles)'.*""".r
      val variants =   """.*'-T' '(GenotypeGVCFs|SelectVariants|VariantFiltration|CombineVariants)'.*""".r

      var vwdpdh: Option[String] = None

      cl = adjustOutput(cl)

      val componentName: String = cl match {
        case hap(cname) => {
          // HaplotypeCaller, RealignerTargetCreator
          var (cl2, vwdpdh2) = adjustScatter(cl)
          cl = cl2
          vwdpdh = vwdpdh2
          cname
        }
        case indel(cname) => {
          cl = adjustTargetIntervals(cl)
          var (cl2, vwdpdh2) = adjustScatter(cl)
          cl = cl2
          vwdpdh = vwdpdh2
          cname
        }
        case cat(cname) => {
          // CatVariants support
          cl = adjustVCF(cl)
          cname
        }
        case mergesam(cname) => {
          cl = adjustMergeSamInput(cl)
          cname
        }
        case variants(cname) => {
          cl = adjustVCF(cl)
          var (cl2, vwdpdh2) = adjustScatter(cl)
          cl = cl2
          vwdpdh = vwdpdh2
          cname
        }
        case _ => {
          throw new QException("Did not recognize tool command line, supports HaplotypeCaller, RealignerTargetCreator, IndelRealigner, GenotypeGVCFs, CatVariants, MergeSamFiles, SelectVariants, VariantFiltration, CombineVariants.")
        }
      }

      val queueConstraints = jobRecord.get("runtime_constraints").asInstanceOf[Map[String, Object]]
      val runtime = new HashMap[String, Object]()
      runtime.putAll(mapAsScalaMap(queueConstraints).filter {
        case ("arvados_sdk_version", _) => true
        case (key, _) => key.startsWith("docker_")
      })
      jobRecord.
        get("script_parameters").asInstanceOf[Map[String, Object]].
        get("runtime_constraints").asInstanceOf[Map[String, Object]].
        get(componentName).asInstanceOf[Map[String, Object]] match {
          case null =>
            println("WARNING: No runtime constraints defined for " + componentName)
          case childConstraints =>
            runtime.putAll(childConstraints)
        }
      runtime.put("max_tasks_per_node", 1:java.lang.Integer)
      body.put("runtime_constraints", runtime)

      val parameters = new HashMap[String, Object]()
      val cmdLine = new ArrayList[String]
      cmdLine.add("/bin/sh")
      cmdLine.add("-c")
      cmdLine.add(cl)
      parameters.put("command", cmdLine)
      vwdpdh match {
        case Some(vwd) => parameters.put("task.vwd", vwd)
        case None => {}
      }

      body.put("script_parameters", parameters)

      val json = new JSONObject(body)
      p = new HashMap[String, Object]()
      p.put("job", json.toString())

      if (function.jobNativeArgs contains "no-reuse") {
        println("Submitting new job")
        p.put("find_or_create", "false")
      } else {
        println("Will reuse past job if available")
        p.put("find_or_create", "true")
      }
      var response: Option[java.util.Map[_, _]] = None

      var retry = 3
      while (retry > 0) {
        try {
          response = Some(arv.call("jobs", "create", p))
          retry = 0
        } catch {
          case e: java.net.SocketTimeoutException => {
            retry -= 1
          }
        }
      }

      response match {
        case Some(r) => {
          jobUuid = r.get("uuid").asInstanceOf[String]
          println("Queued job " + jobUuid)
          updateStatus(RunnerStatus.RUNNING)
        }
        case None => {
          throw new QException("Job creation failed.")
        }
      }
    }
  }

  def linkIndex(fileext:String, suffix: String, joboutput: String) {
    val rx = """(.*/([^/]+))""" + fileext
    outfilePath match {
      case rx.r(d1, d2) => {
        val src = Paths.get("/keep/" + joboutput + "/" + d2 + suffix)
        if (Files.exists(src)) {
          Files.createSymbolicLink(Paths.get(d1 + suffix), src)
        }
      }
      case _ => {}
    }
  }

  def updateJobStatus() = {
    arv.synchronized {
      val p = new HashMap[String, Object]()
      p.put("uuid", jobUuid)
      val response = arv.call("jobs", "get", p)

      var returnStatus: RunnerStatus.Value = null
      val state = response.get("state")

      state match {
        case "Queued" => returnStatus = RunnerStatus.RUNNING
        case "Running" => returnStatus = RunnerStatus.RUNNING
        case "Complete" => {
          println("Job " + jobUuid + " " + state)

          jobs += (workdir -> response.get("output").asInstanceOf[String])

          val writer = new PrintWriter(function.jobOutputFile.getPath, "UTF-8")
          writer.println("Job log for " + jobUuid + " in " + response.get("log") + "/" + response.get("uuid") + ".log.txt")
          writer.close()

          Files.createSymbolicLink(Paths.get(outfilePath), Paths.get("/keep/" + response.get("output") + "/" + outfileName))

          linkIndex(".vcf", ".vcf.idx", response.get("output").asInstanceOf[String])
          linkIndex(".bam", ".bai", response.get("output").asInstanceOf[String])

          returnStatus = RunnerStatus.DONE
        }
        case "Failed" | "Cancelled" => {
          println("Job " + jobUuid + " " + state)

          val writer = new PrintWriter(function.jobOutputFile.getPath, "UTF-8")
          writer.println("Job log for " + jobUuid + " in " + response.get("log") + "/" + response.get("uuid") + ".log.txt")
          writer.close()

          returnStatus = RunnerStatus.FAILED
        }
      }

      updateStatus(returnStatus)

      true
    }
  }

  def tryStop() {
    try {
      val p = new HashMap[String, Object]()
      p.put("uuid", jobUuid)
      p.put("job", "")
      val response = arv.call("jobs", "cancel", p)
    } catch {
      case e: com.google.api.client.http.HttpResponseException => {}
    }
  }
}
