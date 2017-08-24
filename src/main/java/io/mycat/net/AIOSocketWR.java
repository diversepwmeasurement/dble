package io.mycat.net;

import io.mycat.util.TimeUtil;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

public class AIOSocketWR extends SocketWR {
    private static final AIOReadHandler AIO_READ_HANDLER = new AIOReadHandler();
    private static final AIOWriteHandler AIO_WRITE_HANDLER = new AIOWriteHandler();
    private final AsynchronousSocketChannel channel;
    protected final AbstractConnection con;
    protected final AtomicBoolean writing = new AtomicBoolean(false);


    public AIOSocketWR(AbstractConnection conn) {
        channel = (AsynchronousSocketChannel) conn.getChannel();
        this.con = conn;
    }

    @Override
    public void asynRead() {
        ByteBuffer theBuffer = con.readBuffer;
        if (theBuffer == null) {
            theBuffer = con.processor.getBufferPool().allocate(con.processor.getBufferPool().getChunkSize());
            con.readBuffer = theBuffer;
            channel.read(theBuffer, this, AIO_READ_HANDLER);

        } else if (theBuffer.hasRemaining()) {
            channel.read(theBuffer, this, AIO_READ_HANDLER);
        } else {
            throw new java.lang.IllegalArgumentException("full buffer to read ");
        }

    }

    private void asynWrite(final ByteBuffer buffer) {

        buffer.flip();
        this.channel.write(buffer, this, AIO_WRITE_HANDLER);


    }

    /**
     * return true ,means no more data
     *
     * @return
     */
    private boolean write0() {
        if (!writing.compareAndSet(false, true)) {
            return false;
        }
        ByteBuffer theBuffer = con.writeBuffer;
        if (theBuffer == null || !theBuffer.hasRemaining()) { // writeFinished,但要区分bufer是否NULL，不NULL，要回收
            if (theBuffer != null) {
                con.recycle(theBuffer);
                con.writeBuffer = null;

            }
            // poll again
            ByteBuffer buffer = con.writeQueue.poll();
            // more data
            if (buffer != null) {
                if (buffer.limit() == 0) {
                    con.recycle(buffer);
                    con.writeBuffer = null;
                    con.close("quit cmd");
                    writing.set(false);
                    return true;
                } else {
                    con.writeBuffer = buffer;
                    asynWrite(buffer);
                    return false;
                }
            } else {
                // no buffer
                writing.set(false);
                return true;
            }
        } else {
            theBuffer.compact();
            asynWrite(theBuffer);
            return false;
        }

    }

    protected void onWriteFinished(int result) {

        con.netOutBytes += result;
        con.processor.addNetOutBytes(result);
        con.lastWriteTime = TimeUtil.currentTimeMillis();
        boolean noMoreData = this.write0();
        if (noMoreData) {
            this.doNextWriteCheck();
        }

    }

    public void doNextWriteCheck() {

        boolean noMoreData = false;
        noMoreData = this.write0();
        if (noMoreData && !con.writeQueue.isEmpty()) {
            this.write0();
        }


    }
}


