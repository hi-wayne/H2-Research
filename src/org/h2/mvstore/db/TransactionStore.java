/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.h2.mvstore.Cursor;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVMap.Builder;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.ObjectDataType;
import org.h2.util.New;

/**
 * A store that supports concurrent transactions.
 */
public class TransactionStore {

    private static final String LAST_TRANSACTION_ID = "lastTransactionId";

    // TODO should not be hard-coded
    private static final int MAX_UNSAVED_PAGES = 4 * 1024;

    /**
     * The store.
     */
    final MVStore store;

    /**
     * The persisted map of prepared transactions.
     * Key: transactionId, value: [ status, name ].
     */
    final MVMap<Long, Object[]> preparedTransactions;

    /**
     * The undo log.
     * Key: [ transactionId, logId ], value: [ opType, mapId, key, oldValue ].
     */
    final MVMap<long[], Object[]> undoLog;

    /**
     * The lock timeout in milliseconds. 0 means timeout immediately.
     */
    long lockTimeout; //没有看到在哪里有赋值，就是默认值0

    /**
     * The transaction settings. The entry "lastTransaction" contains the last
     * transaction id.
     */
    private final MVMap<String, String> settings; //只有一个lastTransactionId作为key，没有其他key了

    private long lastTransactionIdStored;

    private long lastTransactionId;

    private long firstOpenTransaction = -1;

    /**
     * Create a new transaction store.
     *
     * @param store the store
     */
    public TransactionStore(MVStore store) {
        this(store, new ObjectDataType());
    }

    /**
     * Create a new transaction store.
     *
     * @param store the store
     * @param keyType the data type for map keys
     */
    public TransactionStore(MVStore store, DataType keyType) {
        this.store = store;
        settings = store.openMap("settings");
        preparedTransactions = store.openMap("openTransactions",
                new MVMap.Builder<Long, Object[]>());
        // TODO commit of larger transaction could be faster if we have one undo
        // log per transaction, or a range delete operation for maps
        VersionedValueType oldValueType = new VersionedValueType(keyType);
        ArrayType valueType = new ArrayType(new DataType[]{
                new ObjectDataType(), new ObjectDataType(), keyType,
                oldValueType
        });
        MVMap.Builder<long[], Object[]> builder =
                new MVMap.Builder<long[], Object[]>().
                valueType(valueType);
        // TODO escape other map names, to avoid conflicts
        undoLog = store.openMap("undoLog", builder);
        init();
    }

    private void init() {
        String s = settings.get(LAST_TRANSACTION_ID);
        if (s != null) {
            lastTransactionId = Long.parseLong(s);
            lastTransactionIdStored = lastTransactionId;
        }
        Long lastKey = preparedTransactions.lastKey();
        if (lastKey != null && lastKey.longValue() > lastTransactionId) {
            throw DataUtils.newIllegalStateException("Last transaction not stored");
        }
        if (undoLog.size() > 0) {
            long[] key = undoLog.firstKey();
            firstOpenTransaction = key[0];
        }
    }

    /**
     * Get the list of unclosed transactions that have pending writes.
     *
     * @return the list of transactions (sorted by id)
     */
    public synchronized List<Transaction> getOpenTransactions() { //只在测试中用到
        ArrayList<Transaction> list = New.arrayList();
        long[] key = undoLog.firstKey();
        while (key != null) {
            long transactionId = key[0];
            long[] end = { transactionId, Long.MAX_VALUE };
            key = undoLog.floorKey(end);
            long logId = key[1] + 1;
            Object[] data = preparedTransactions.get(transactionId);
            int status;
            String name;
            if (data == null) {
                status = Transaction.STATUS_OPEN;
                name = null;
            } else {
                status = (Integer) data[0];
                name = (String) data[1];
            }
            Transaction t = new Transaction(this, transactionId, status, name, logId);
            list.add(t);
            key = undoLog.higherKey(end);
        }
        return list;
    }

    /**
     * Close the transaction store.
     */
    public synchronized void close() { //只见到在测试中使用
        // to avoid losing transaction ids
        settings.put(LAST_TRANSACTION_ID, "" + lastTransactionId);
        store.commit();
    }

    /**
     * Begin a new transaction.
     *
     * @return the transaction
     */
    public synchronized Transaction begin() {
        long transactionId = lastTransactionId++;
        if (lastTransactionId > lastTransactionIdStored) {
            lastTransactionIdStored += 64;
            settings.put(LAST_TRANSACTION_ID, "" + lastTransactionIdStored);
        }
        int status = Transaction.STATUS_OPEN;
        return new Transaction(this, transactionId, status, null, 0);
    }

    private void commitIfNeeded() {
        if (store.getUnsavedPageCount() > MAX_UNSAVED_PAGES) {
            store.commit();
            store.store();
        }
    }

    /**
     * Store a transaction.
     *
     * @param t the transaction
     */
    void storeTransaction(Transaction t) {
        if (t.getStatus() == Transaction.STATUS_PREPARED || t.getName() != null) {
            Object[] v = { t.getStatus(), t.getName() };
            preparedTransactions.put(t.getId(), v);
        }
    }

    /**
     * Log an entry.
     *
     * @param t the transaction
     * @param logId the log id
     * @param opType the operation type
     * @param mapId the map id
     * @param key the key
     * @param oldValue the old value
     */
    void log(Transaction t, long logId, int opType, int mapId,
            Object key, Object oldValue) {
        commitIfNeeded();
        long[] undoKey = { t.getId(), logId };
        Object[] log = new Object[] { opType, mapId, key, oldValue };
        undoLog.put(undoKey, log);
        if (firstOpenTransaction == -1 || t.getId() < firstOpenTransaction) {
            firstOpenTransaction = t.getId();
        }
    }

    /**
     * Commit a transaction.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     */
    void commit(Transaction t, long maxLogId) {
        if (store.isClosed()) {
            return;
        }
        for (long logId = 0; logId < maxLogId; logId++) {
            long[] undoKey = new long[] {
                    t.getId(), logId };
            commitIfNeeded();
            Object[] op = undoLog.get(undoKey);
            int opType = (Integer) op[0];
            if (opType == Transaction.OP_REMOVE) {
                int mapId = (Integer) op[1];
                Map<String, String> meta = store.getMetaMap();
                String m = meta.get("map." + mapId);
                String mapName = DataUtils.parseMap(m).get("name");
                MVMap<Object, VersionedValue> map = store.openMap(mapName);
                Object key = op[2];
                VersionedValue value = map.get(key);
                // possibly the entry was added later on
                // so we have to check
                if (value.value == null) {
                    // remove the value
                    map.remove(key);
                }
            }
            undoLog.remove(undoKey);
        }
        endTransaction(t);
    }

    /**
     * Check whether the given transaction id is still open and contains log
     * entries.
     *
     * @param transactionId the transaction id
     * @return true if it is open
     */
    boolean isTransactionOpen(long transactionId) {
        if (transactionId < firstOpenTransaction) {
            return false;
        }
        if (firstOpenTransaction == -1) {
            if (undoLog.size() == 0) {
                return false;
            }
            long[] key = undoLog.firstKey();
            firstOpenTransaction = key[0];
        }
        if (firstOpenTransaction == transactionId) {
            return true;
        }
        long[] key = { transactionId, -1 };
        key = undoLog.higherKey(key);
        return key != null && key[0] == transactionId;
    }

    /**
     * End this transaction
     *
     * @param t the transaction
     */
    void endTransaction(Transaction t) {
        if (t.getStatus() == Transaction.STATUS_PREPARED) {
            preparedTransactions.remove(t.getId());
        }
        t.setStatus(Transaction.STATUS_CLOSED);
        if (t.getId() == firstOpenTransaction) {
            firstOpenTransaction = -1;
        }
    }

    /**
     * Rollback to an old savepoint.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     * @param toLogId the log id to roll back to
     */
    void rollbackTo(Transaction t, long maxLogId, long toLogId) {
        for (long logId = maxLogId - 1; logId >= toLogId; logId--) {
            commitIfNeeded();
            Object[] op = undoLog.get(new long[] {
                    t.getId(), logId });
            int mapId = ((Integer) op[1]).intValue();
            // TODO open map by id if possible
            Map<String, String> meta = store.getMetaMap();
            String m = meta.get("map." + mapId);
            String mapName = DataUtils.parseMap(m).get("name");
            MVMap<Object, VersionedValue> map = store.openMap(mapName);
            Object key = op[2];
            VersionedValue oldValue = (VersionedValue) op[3];
            if (oldValue == null) {
                // this transaction added the value
                map.remove(key);
            } else {
                // this transaction updated the value
                map.put(key, oldValue);
            }
            undoLog.remove(op);
        }
    }

    /**
     * Get the set of changed maps.
     *
     * @param t the transaction
     * @param maxLogId the maximum log id
     * @param toLogId the minimum log id
     * @return the set of changed maps
     */
    HashSet<String> getChangedMaps(Transaction t, long maxLogId, long toLogId) {
        HashSet<String> set = New.hashSet();
        for (long logId = maxLogId - 1; logId >= toLogId; logId--) {
            Object[] op = undoLog.get(new long[] {
                    t.getId(), logId });
            int mapId = ((Integer) op[1]).intValue();
            // TODO open map by id if possible
            Map<String, String> meta = store.getMetaMap();
            String m = meta.get("map." + mapId);
            String mapName = DataUtils.parseMap(m).get("name");
            set.add(mapName);
        }
        return set;
    }

    /**
     * A transaction.
     */
    public static class Transaction {

        /**
         * The status of an open transaction.
         */
        public static final int STATUS_OPEN = 0;

        /**
         * The status of a prepared transaction.
         */
        public static final int STATUS_PREPARED = 1;

        /**
         * The status of a closed transaction (committed or rolled back).
         */
        public static final int STATUS_CLOSED = 2;

        /**
         * The operation type for changes in a map.
         */
        static final int OP_REMOVE = 0, OP_ADD = 1, OP_SET = 2;

        /**
         * The transaction store.
         */
        final TransactionStore store;

        /**
         * The version of the store at the time the transaction was started.
         */
        final long startVersion;

        /**
         * The transaction id.
         */
        final long transactionId;

        /**
         * The log id of the last entry in the undo log map.
         */
        long logId;

        private int status;

        private String name;

        Transaction(TransactionStore store, long transactionId, int status, String name, long logId) {
            this.store = store;
            this.startVersion = store.store.getCurrentVersion();
            this.transactionId = transactionId;
            this.status = status;
            this.name = name;
            this.logId = logId;
        }

        public long getId() {
            return transactionId;
        }

        public int getStatus() {
            return status;
        }

        void setStatus(int status) {
            this.status = status;
        }

        public void setName(String name) {
            checkOpen();
            this.name = name;
            store.storeTransaction(this);
        }

        public String getName() {
            return name;
        }

        /**
         * Create a new savepoint.
         *
         * @return the savepoint id
         */
        public long setSavepoint() {
            checkOpen();
            return logId;
        }

        /**
         * Add a log entry.
         *
         * @param opType the operation type
         * @param mapId the map id
         * @param key the key
         * @param oldValue the old value
         */
        void log(int opType, int mapId, Object key, Object oldValue) {
            store.log(this, logId++, opType, mapId, key, oldValue);
        }

        /**
         * Open a data map.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param name the name of the map
         * @return the transaction map
         */
        public <K, V> TransactionMap<K, V> openMap(String name) {
            checkOpen();
            return new TransactionMap<K, V>(this, name, new ObjectDataType(),
                    new ObjectDataType());
        }

        /**
         * Open the map to store the data.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param name the name of the map
         * @param builder the builder
         * @return the transaction map
         */
        public <K, V> TransactionMap<K, V> openMap(String name, Builder<K, V> builder) {
            checkOpen();
            DataType keyType = builder.getKeyType();
            if (keyType == null) {
                keyType = new ObjectDataType();
            }
            DataType valueType = builder.getValueType();
            if (valueType == null) {
                valueType = new ObjectDataType();
            }
            return new TransactionMap<K, V>(this, name, keyType, valueType);
        }

        /**
         * Prepare the transaction. Afterwards, the transaction can only be
         * committed or rolled back.
         */
        public void prepare() {
            checkOpen();
            status = STATUS_PREPARED;
            store.storeTransaction(this);
        }

        /**
         * Commit the transaction. Afterwards, this transaction is closed.
         */
        public void commit() {
            checkNotClosed();
            store.commit(this, logId);
        }

        /**
         * Roll back to the given savepoint. This is only allowed if the
         * transaction is open.
         *
         * @param savepointId the savepoint id
         */
        public void rollbackToSavepoint(long savepointId) {
            checkOpen();
            store.rollbackTo(this, logId, savepointId);
            logId = savepointId;
        }

        /**
         * Roll the transaction back. Afterwards, this transaction is closed.
         */
        public void rollback() {
            checkNotClosed();
            store.rollbackTo(this, logId, 0);
            store.endTransaction(this);
        }

        /**
         * Get the set of changed maps starting at the given savepoint up to
         * now.
         *
         * @param savepointId the savepoint id, 0 meaning the beginning of the
         *            transaction
         * @return the set of changed maps
         */
        public Set<String> getChangedMaps(long savepointId) {
            return store.getChangedMaps(this, logId, savepointId);
        }

        /**
         * Check whether this transaction is still open.
         */
        void checkOpen() {
            if (status != STATUS_OPEN) {
                throw DataUtils.newIllegalStateException("Transaction is closed");
            }
        }

        /**
         * Check whether this transaction is open or prepared.
         */
        void checkNotClosed() {
            if (status == STATUS_CLOSED) {
                throw DataUtils.newIllegalStateException("Transaction is closed");
            }
        }

    }

    /**
     * A map that supports transactions.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class TransactionMap<K, V> {

        /**
         * The map used for writing (the latest version).
         * <p>
         * Key: key the key of the data.
         * Value: { transactionId, oldVersion, value } //oldVersion实际上就是logId，logId在Transaction.log方法中递增
         */
        final MVMap<K, VersionedValue> map;

        private Transaction transaction; //这个字段也适合加final

        private final int mapId;

        /**
         * If a record was read that was updated by this transaction, and the
         * update occurred before this log id, the older version is read. This
         * is so that changes are not immediately visible, to support statement
         * processing (for example "update test set id = id + 1").
         */
        private long readLogId = Long.MAX_VALUE; //只能读到logId比readLogId小的记录

        TransactionMap(Transaction transaction, String name, DataType keyType,
                DataType valueType) {
            this.transaction = transaction;
            VersionedValueType vt = new VersionedValueType(valueType);
            MVMap.Builder<K, VersionedValue> builder = new MVMap.Builder<K, VersionedValue>()
                    .keyType(keyType).valueType(vt);
            map = transaction.store.store.openMap(name, builder);
            mapId = map.getId();
        }

        private TransactionMap(Transaction transaction, MVMap<K, VersionedValue> map, int mapId) {
            this.transaction = transaction;
            this.map = map;
            this.mapId = mapId;
        }

        /**
         * Set the savepoint. Afterwards, reads are based on the specified
         * savepoint.
         *
         * @param savepoint the savepoint
         */
        public void setSavepoint(long savepoint) { //只能读到logId比savepoint小的记录
            this.readLogId = savepoint;
        }

        /**
         * Get a clone of this map for the given transaction.
         *
         * @param transaction the transaction
         * @param savepoint the savepoint
         * @return the map
         */
        public TransactionMap<K, V> getInstance(Transaction transaction, long savepoint) {
            TransactionMap<K, V> m = new TransactionMap<K, V>(transaction, map, mapId);
            m.setSavepoint(savepoint);
            return m;
        }

        /**
         * Get the size of the map as seen by this transaction.
         *
         * @return the size
         */
        //为什么不直接用map.getSize()，因为有可能其他事务同时操作map，但是没提交，
        //所以当前事务只能看到自己提交的，所以要调用一下get(key)，而get(key)里会有readLogId
        public long getSize() {
            // TODO this method is very slow
            long size = 0;
            Cursor<K> cursor = map.keyIterator(null);
            while (cursor.hasNext()) {
                K key = cursor.next();
                if (get(key) != null) {
                    size++;
                }
            }
            return size;
        }

        /**
         * Remove an entry.
         * <p>
         * If the row is locked, this method will retry until the row could be
         * updated or until a lock timeout.
         *
         * @param key the key
         * @throws IllegalStateException if a lock timeout occurs
         */
        public V remove(K key) {
            return set(key, null);
        }

        /**
         * Update the value for the given key.
         * <p>
         * If the row is locked, this method will retry until the row could be
         * updated or until a lock timeout.
         *
         * @param key the key
         * @param value the new value (not null)
         * @return the old value
         * @throws IllegalStateException if a lock timeout occurs
         */
        public V put(K key, V value) {
            DataUtils.checkArgument(value != null, "The value may not be null");
            return set(key, value);
        }

        private V set(K key, V value) {
            transaction.checkOpen();
            long start = 0;
            while (true) {
                V old = get(key);
                boolean ok = trySet(key, value, false);
                if (ok) {
                    return old;
                }
                // an uncommitted transaction:
                // wait until it is committed, or until the lock timeout
                long timeout = transaction.store.lockTimeout;
                if (timeout == 0) {
                    throw DataUtils.newIllegalStateException("Lock timeout");
                }
                if (start == 0) {
                    start = System.currentTimeMillis();
                } else {
                    long t = System.currentTimeMillis() - start;
                    if (t > timeout) {
                        throw DataUtils.newIllegalStateException("Lock timeout");
                    }
                    // TODO use wait/notify instead, or remove the feature
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }

        /**
         * Try to remove the value for the given key.
         * <p>
         * This will fail if the row is locked by another transaction (that
         * means, if another open transaction changed the row).
         *
         * @param key the key
         * @return whether the entry could be removed
         */
        public boolean tryRemove(K key) { //tryRemove、tryPut与remove、put的差别是try开头的不会等待琐超时
            return trySet(key, null, false);
        }

        /**
         * Try to update the value for the given key.
         * <p>
         * This will fail if the row is locked by another transaction (that
         * means, if another open transaction changed the row).
         *
         * @param key the key
         * @param value the new value
         * @return whether the entry could be updated
         */
        public boolean tryPut(K key, V value) {
            DataUtils.checkArgument(value != null, "The value may not be null");
            return trySet(key, value, false);
        }

        /**
         * Try to set or remove the value. When updating only unchanged entries,
         * then the value is only changed if it was not changed after opening
         * the map.
         *
         * @param key the key
         * @param value the new value (null to remove the value)
         * @param onlyIfUnchanged only set the value if it was not changed (by
         *            this or another transaction) since the map was opened
         * @return true if the value was set
         */
        public boolean trySet(K key, V value, boolean onlyIfUnchanged) { //只有在测试例子中看到设置onlyIfUnchanged为true
            VersionedValue current = map.get(key);
            if (onlyIfUnchanged) {
            	//值是:Value: { transactionId, oldVersion, value } oldVersion实际上就是logId，logId在Transaction.log方法中递增
                VersionedValue old = getValue(key, readLogId);
                //onlyIfUnchanged的意思是current(注意不是参数value)与原先的值相等时才设置，
                //所以如果current与原先的值不相等，那么就可以快速确定返回值
                if (!map.areValuesEqual(old, current)) {
                    long tx = current.transactionId;
                    if (tx == transaction.transactionId) {
                        if (value == null) {
                            // ignore removing an entry
                            // if it was added or changed
                            // in the same statement
                            return true;
                        } else if (current.value == null) {
                            // add an entry that was removed
                            // in the same statement
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
            }
            int opType;
            if (current == null || current.value == null) {
                if (value == null) {
                    // remove a removed value
                    opType = Transaction.OP_SET;
                } else {
                    opType = Transaction.OP_ADD;
                }
            } else {
                if (value == null) {
                    opType = Transaction.OP_REMOVE;
                } else {
                    opType = Transaction.OP_SET;
                }
            }
            VersionedValue newValue = new VersionedValue();
            newValue.transactionId = transaction.transactionId;
            newValue.logId = transaction.logId;
            newValue.value = value;
            if (current == null) { //指的是原来的map中没有值
                // a new value
                VersionedValue old = map.putIfAbsent(key, newValue);
                if (old == null) {
                    transaction.log(opType, mapId, key, current); //在undo log中记录下之前的值
                    return true;
                }
                return false; //被其他线程抢先设置了
            }
            long tx = current.transactionId;
            if (tx == transaction.transactionId) { //同一事务内先后修改相同的key
                // added or updated by this transaction
                if (map.replace(key, current, newValue)) {
                    transaction.log(opType, mapId, key, current);
                    return true;
                }
                // strange, somebody overwrite the value
                // even thought the change was not committed
                return false; //被其他线程抢先修改了
            }
            // added or updated by another transaction
            boolean open = transaction.store.isTransactionOpen(tx);
            if (!open) { //前一次的事务已提交
                // the transaction is committed:
                // overwrite the value
                if (map.replace(key, current, newValue)) {
                    transaction.log(opType, mapId, key, current);
                    return true;
                }
                // somebody else was faster
                return false;
            }
            // the transaction is not yet committed
            return false; //前面的事务未提交，当前事务必须等待，然后才能修改相同的key
        }

        /**
         * Get the value for the given key at the time when this map was opened.
         *
         * @param key the key
         * @return the value or null
         */
        public V get(K key) {
            return get(key, readLogId);
        }

        /**
         * Get the most recent value for the given key.
         *
         * @param key the key
         * @return the value or null
         */
        public V getLatest(K key) {
            return get(key, Long.MAX_VALUE);
        }

        /**
         * Whether the map contains the key.
         *
         * @param key the key
         * @return true if the map contains an entry for this key
         */
        public boolean containsKey(K key) {
            return get(key) != null;
        }

        /**
         * Get the value for the given key.
         *
         * @param key the key
         * @param maxLogId the maximum log id
         * @return the value or null
         */
        @SuppressWarnings("unchecked")
        public V get(K key, long maxLogId) {
            transaction.checkOpen();
            VersionedValue data = getValue(key, maxLogId);
            return data == null ? null : (V) data.value;
        }

        //见org.h2.test.store.TestTransactionStore.testKeyIterator()里的测试
        //当执行到第三个tx = ts.begin()时tx != transaction.transactionId
        //所以就从第二个tx = ts.begin()对应的undoLog中取出value
        private VersionedValue getValue(K key, long maxLog) {
            VersionedValue data = map.get(key);
            //System.out.println(map.getRoot());
            //System.out.println(map.getOldRoots());
            while (true) {
                long tx;
                if (data == null) {
                    // doesn't exist or deleted by a committed transaction
                    return null;
                }
                tx = data.transactionId;
                long logId = data.logId;
                if (tx == transaction.transactionId) { //是map对应的transactionId
                    // added by this transaction
                    if (logId < maxLog) {
                        return data;
                    }
                }
                // added or updated by another transaction
                boolean open = transaction.store.isTransactionOpen(tx);
                if (!open) { //如果对应的事务id不在当前的事务列表中，说明此事务已提交了
                    // it is committed
                    return data;
                }
                //否则从undoLog中取
                // get the value before the uncommitted transaction
                long[] x = new long[] { tx, logId };
                Object[] d = transaction.store.undoLog.get(x);
                data = (VersionedValue) d[3];
            }
        }


        /**
         * Rename the map.
         *
         * @param newMapName the new map name
         */
        public void renameMap(String newMapName) {
            // TODO rename maps transactionally
            map.renameMap(newMapName);
        }

        /**
         * Check whether this map is closed.
         *
         * @return true if closed
         */
        public boolean isClosed() {
            return map.isClosed();
        }

        /**
         * Remove the map.
         */
        public void removeMap() {
            // TODO remove in a transaction
            map.removeMap();
        }

        /**
         * Clear the map.
         */
        public void clear() {
            // TODO truncate transactionally
            map.clear();
        }

        /**
         * Get the first key.
         *
         * @return the first key, or null if empty
         */
        public K firstKey() {
            // TODO transactional firstKey
            return map.firstKey();
        }

        /**
         * Get the last key.
         *
         * @return the last key, or null if empty
         */
        public K lastKey() {
            // TODO transactional lastKey
            return map.lastKey();
        }

        /**
         * Iterate over all keys.
         *
         * @param from the first key to return
         * @return the iterator
         */
        public Iterator<K> keyIterator(final K from) { //包含from
            return new Iterator<K>() {
                private final Cursor<K> cursor = map.keyIterator(from);
                private K current;

                {
                    fetchNext();
                }

                private void fetchNext() {
                    while (cursor.hasNext()) {
                        current = cursor.next();
                        if (containsKey(current)) {
                            return;
                        }
                    }
                    current = null;
                }

                @Override
                public boolean hasNext() {
                    return current != null;
                }

                @Override
                public K next() {
                    K result = current;
                    fetchNext();
                    return result;
                }

                @Override
                public void remove() {
                    throw DataUtils.newUnsupportedOperationException(
                            "Removing is not supported");
                }
            };
        }

        /**
         * Get the smallest key that is larger or equal to this key.
         *
         * @param key the key (may not be null)
         * @return the result
         */
        public K ceilingKey(K key) { //>=key的最小的那一个
            // TODO transactional ceilingKey
            return map.ceilingKey(key);
        }

        /**
         * Get the smallest key that is larger than the given key, or null if no
         * such key exists.
         *
         * @param key the key (may not be null)
         * @return the result
         */
        public K higherKey(K key) { //>key的最小的那一个(注:上面的ceilingKey没有等于)
            // TODO transactional higherKey
            return map.higherKey(key);
        }

        /**
         * Get the largest key that is smaller than the given key, or null if no
         * such key exists.
         *
         * @param key the key (may not be null)
         * @return the result
         */
        public K lowerKey(K key) { //<key的最大那一个
            // TODO Auto-generated method stub
            return map.lowerKey(key);
        }

        public Transaction getTransaction() {
            return transaction;
        }

    }

    /**
     * A versioned value (possibly null). It contains a pointer to the old
     * value, and the value itself.
     */
    static class VersionedValue {

        /**
         * The transaction id.
         */
        public long transactionId;

        /**
         * The log id.
         */
        public long logId;

        /**
         * The value.
         */
        public Object value;

		@Override
		public String toString() { //我加上的
			return "VersionedValue[transactionId=" + transactionId + ", logId=" + logId + ", value=" + value + "]";
		}
    }

    /**
     * The value type for a versioned value.
     */
    public static class VersionedValueType implements DataType {

        private final DataType valueType;

        VersionedValueType(DataType valueType) {
            this.valueType = valueType;
        }

        @Override
        public int getMemory(Object obj) {
            VersionedValue v = (VersionedValue) obj;
            return valueType.getMemory(v.value) + 16;
        }

        @Override
        public int compare(Object aObj, Object bObj) {
            if (aObj == bObj) {
                return 0;
            }
            VersionedValue a = (VersionedValue) aObj;
            VersionedValue b = (VersionedValue) bObj;
            long comp = a.transactionId - b.transactionId;
            if (comp == 0) {
                comp = a.logId - b.logId;
                if (comp == 0) {
                    return valueType.compare(a.value, b.value);
                }
            }
            return Long.signum(comp);
        }

        @Override
        public ByteBuffer write(ByteBuffer buff, Object obj) {
            VersionedValue v = (VersionedValue) obj;
            DataUtils.writeVarLong(buff, v.transactionId);
            DataUtils.writeVarLong(buff, v.logId);
            return valueType.write(buff, v.value);
        }

        @Override
        public Object read(ByteBuffer buff) {
            VersionedValue v = new VersionedValue();
            v.transactionId = DataUtils.readVarLong(buff);
            v.logId = DataUtils.readVarLong(buff);
            v.value = valueType.read(buff);
            return v;
        }

    }

    /**
     * A data type that contains an array of objects with the specified data
     * types.
     */
    public static class ArrayType implements DataType {

        private final int arrayLength;
        private final DataType[] elementTypes;

        ArrayType(DataType[] elementTypes) {
            this.arrayLength = elementTypes.length;
            this.elementTypes = elementTypes;
        }

        @Override
        public int getMemory(Object obj) {
            Object[] array = (Object[]) obj;
            int size = 0;
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                Object o = array[i];
                if (o != null) {
                    size += t.getMemory(o);
                }
            }
            return size;
        }

        @Override
        public int compare(Object aObj, Object bObj) {
            if (aObj == bObj) {
                return 0;
            }
            Object[] a = (Object[]) aObj;
            Object[] b = (Object[]) bObj;
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                int comp = t.compare(a[i], b[i]);
                if (comp != 0) {
                    return comp;
                }
            }
            return 0;
        }

        @Override
        public ByteBuffer write(ByteBuffer buff, Object obj) {
            Object[] array = (Object[]) obj;
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                Object o = array[i];
                if (o == null) {
                    buff.put((byte) 0);
                } else {
                    buff.put((byte) 1);
                    buff = t.write(buff, o);
                }
            }
            return buff;
        }

        @Override
        public Object read(ByteBuffer buff) {
            Object[] array = new Object[arrayLength];
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                if (buff.get() == 1) {
                    array[i] = t.read(buff);
                }
            }
            return array;
        }

    }

}

