package org.broadinstitute.sting.gatk.refdata.utils;

import org.broadinstitute.sting.utils.GenomeLoc;

import java.util.Comparator;
import java.util.LinkedList;


/**
 * 
 * @author aaron 
 * 
 * Class FlashBackIterator
 *
 * better than acid washed jeans...more like a Delorean that flies through time
 *
 * This iterator buffers a certain amount of ROD data to 'flash back' to.  This
 * is needed for using ROD's in read traversals, because between shards we sometimes
 * (actually often) need to go back to before the current iterators location and
 * get RODs that overlap the current read.
 */
public class FlashBackIterator implements LocationAwareSeekableRODIterator {
    private LocationAwareSeekableRODIterator iterator;
    private LinkedList<ComparableList> pastQueue = new LinkedList<ComparableList>();
    private LinkedList<ComparableList> aheadQueue = new LinkedList<ComparableList>();
    private int MAX_QUEUE = 200;

    /**
     * create a flashback iterator
     * @param iterator given a LocationAwareSeekableRODIterator
     */
    public FlashBackIterator(LocationAwareSeekableRODIterator iterator) {
        this.iterator = iterator;
    }

    /**
     * peek at the next location
     * @return
     */
    @Override
    public GenomeLoc peekNextLocation() {
        return (aheadQueue.size() > 0) ? aheadQueue.getFirst().getLocation() : iterator.peekNextLocation();
    }

    /**
     * get the position of this iterator
     * @return
     */
    @Override
    public GenomeLoc position() {
        return (aheadQueue.size() > 0) ? aheadQueue.getFirst().getLocation() : iterator.position();
    }

    /**
     * seek forward on the iterator
     * @param interval the interval to seek to
     * @return a RODRecordList at that location, null otherwise
     */
    @Override
    public RODRecordList seekForward(GenomeLoc interval) {

        RODRecordList lt = iterator.seekForward(interval);
        createPastRecord(lt);
        return lt;
    }

    /**
     * do we have a next record
     * @return true if we have another record
     */
    @Override
    public boolean hasNext() {
        return (aheadQueue.size() > 0 ||  iterator.hasNext());
    }

    /**
     * get the next record
     * @return a RODRecordList
     */
    @Override
    public RODRecordList next() {
        return getNext();
    }

    /**
     * we don't support remove
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException("We don't support remove");
    }

    /**
     * get the next record, either from the queue or from the iterator
     * @return a RODRecordList
     */
    private RODRecordList getNext() {
        if (aheadQueue.size() > 0) {
            RODRecordList ret = aheadQueue.getFirst().getList();
            aheadQueue.removeFirst();
            return ret;
        } else {
            RODRecordList ret = iterator.next();
            createPastRecord(ret);
            return ret;
        }
    }

    private void createPastRecord(RODRecordList ret) {
        ComparableList rec = new ComparableList(ret);
        if (rec.getLocation() != null) pastQueue.addLast(new ComparableList(ret));
        if (pastQueue.size() > this.MAX_QUEUE) pastQueue.removeFirst();
    }

    /**
     * can we flash back to the specified location?
     *
     * @param location the location to try and flash back to
     *
     * @return true if we can, false otherwise
     */
    public boolean canFlashBackTo(GenomeLoc location) {
        GenomeLoc farthestBack = (pastQueue.size() > 0) ? pastQueue.getFirst().getLocation() : iterator.peekNextLocation();
        return (!farthestBack.isPast(location));
    }

    /**
     * flashback! Throws an unsupported operation exception
     *
     * @param location where to flash back to
     */
    public void flashBackTo(GenomeLoc location) {
        if (!canFlashBackTo(location)) throw new UnsupportedOperationException("we can't flash back to " + location);
        if (pastQueue.size()==0) return; // the iterator can do it alone
        while (pastQueue.size() > 0 && !pastQueue.getLast().getLocation().isBefore(location)) {
            aheadQueue.addFirst(pastQueue.getLast());
            pastQueue.removeLast();
        }
    }

    public void close() {
        this.aheadQueue.clear();
        this.pastQueue.clear();
    }
}

/**
 * a list that buffers the location for this rod
 */
class ComparableList implements Comparator<ComparableList> {
    private RODRecordList list;
    private GenomeLoc location = null;
    public ComparableList(RODRecordList list) {
        this.list = list;
        if (list != null && list.size() != 0)
            location = list.getLocation();
    }

    @Override
    public int compare(ComparableList list1, ComparableList list2) {
        if (list1.location == null && list2.location == null)
            return 0;
        if (list1.location == null) return 1;
        if (list2.location == null) return -1;
        return (list1.location.compareTo(list2.location));
    }

    public GenomeLoc getLocation() {
        return location;
    }

    public RODRecordList getList() {
        return list;
    }
}