/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.store;

import java.io.IOException;
import java.io.InputStream;

/**
 * An input stream that reads from a remote LOB.
 */
class LobStorageRemoteInputStream extends InputStream {

    /**
     * The data handler.
     */
    private final DataHandler handler;

    /**
     * The lob id.
     */
    private final long lob;

    private final byte[] hmac;

    /**
     * The position.
     */
    private long pos;

    /**
     * The remaining bytes in the lob.
     */
    private long remainingBytes;

    public LobStorageRemoteInputStream(DataHandler handler, long lob, byte[] hmac, long byteCount) {
        this.handler = handler;
        this.lob = lob;
        this.hmac = hmac;
        remainingBytes = byteCount;
    }

    @Override
    public int read() throws IOException {
        byte[] buff = new byte[1];
        int len = read(buff, 0, 1);
        return len < 0 ? len : (buff[0] & 255);
    }

    @Override
    public int read(byte[] buff) throws IOException {
        return read(buff, 0, buff.length);
    }

    @Override
    public int read(byte[] buff, int off, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        length = (int) Math.min(length, remainingBytes);
        if (length == 0) {
            return -1;
        }
        length = handler.readLob(lob, hmac, pos, buff, off, length);
        remainingBytes -= length;
        if (length == 0) {
            return -1;
        }
        pos += length;
        return length;
    }

    @Override
    public long skip(long n) {
        remainingBytes -= n;
        pos += n;
        return n;
    }

}