package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * GZIP implementation per 
 * <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952</a>, reusing 
 * java's standard CRC32 and Inflater and InflaterInputStream implementations.
 * The main difference is that this implementation allows its state to be 
 * reset to initial values, and hence reused, while the standard 
 * GZIPInputStream reads the GZIP header from the stream on instantiation.
 *
 */
public class ResettableGZIPInputStream extends InflaterInputStream {
    private static final int FOOTER_SIZE = 8; // CRC32 + ISIZE
    private static final boolean DEBUG = false;
    /** keep a typesafe copy of (LookaheadInputStream)in */
    private LookaheadInputStream _lookaheadStream;
    private CRC32 _crc32;
    private byte _buf1[] = new byte[1];
    private boolean _complete;
    
    /**
     * Build a new GZIP stream without a bound compressed stream.  You need
     * to initialize this with initialize(compressedStream) when you want to
     * decompress a stream.
     */
    public ResettableGZIPInputStream() {
        super(new LookaheadInputStream(FOOTER_SIZE), new Inflater(true));
        _lookaheadStream = (LookaheadInputStream)in;
        _crc32 = new CRC32();
        _complete = false;
    }
    public ResettableGZIPInputStream(InputStream compressedStream) throws IOException {
        this();
        initialize(compressedStream);
    }
    
    /**
     * Blocking call to initialize this stream with the data from the given
     * compressed stream.
     *
     */
    public void initialize(InputStream compressedStream) throws IOException {
        len = 0;
        inf.reset();
        _complete = false;
        _crc32.reset();
        _buf1[0] = 0x0;
        // blocking call to read the footer/lookahead, and use the compressed
        // stream as the source for further lookahead bytes
        _lookaheadStream.initialize(compressedStream);
        // now blocking call to read and verify the GZIP header from the
        // lookahead stream
        verifyHeader();
    }
    
    @Override
    public int read() throws IOException {
        if (_complete) {
            // shortcircuit so the inflater doesn't try to refill 
            // with the footer's data (which would fail, causing ZLIB err)
            return -1;
        }
        int read = read(_buf1, 0, 1);
        if (read == -1)
            return -1;
        else
            return _buf1[0];
    }
    
    @Override
    public int read(byte buf[]) throws IOException {
        return read(buf, 0, buf.length);
    }
    @Override
    public int read(byte buf[], int off, int len) throws IOException {
        if (_complete) {
            // shortcircuit so the inflater doesn't try to refill 
            // with the footer's data (which would fail, causing ZLIB err)
            return -1;
        }
        int read = super.read(buf, off, len);
        if (read == -1) {
            verifyFooter();
            return -1;
        } else {
            _crc32.update(buf, off, read);
            if (_lookaheadStream.getEOFReached()) {
                verifyFooter();
                inf.reset(); // so it doesn't bitch about missing data...
                _complete = true;
            }
            return read;
        }
    }
    
    long getCurrentCRCVal() { return _crc32.getValue(); }
    
    void verifyFooter() throws IOException {
        byte footer[] = _lookaheadStream.getFooter();
        
        long expectedCRCVal = _crc32.getValue();
        
        // damn RFC writing their bytes backwards...
        if (!(footer[0] == (byte)(expectedCRCVal & 0xFF)))
            throw new IOException("footer[0]=" + footer[0] + " expectedCRC[0]="
                                  + (expectedCRCVal & 0xFF));
        if (!(footer[1] == (byte)(expectedCRCVal >>> 8)))
            throw new IOException("footer[1]=" + footer[1] + " expectedCRC[1]="
                                  + ((expectedCRCVal >>> 8) & 0xFF));
        if (!(footer[2] == (byte)(expectedCRCVal >>> 16)))
            throw new IOException("footer[2]=" + footer[2] + " expectedCRC[2]="
                                  + ((expectedCRCVal >>> 16) & 0xFF));
        if (!(footer[3] == (byte)(expectedCRCVal >>> 24)))
            throw new IOException("footer[3]=" + footer[3] + " expectedCRC[3]="
                                  + ((expectedCRCVal >>> 24) & 0xFF));
        
        int expectedSizeVal = inf.getTotalOut();
        
        if (!(footer[4] == (byte)expectedSizeVal))
            throw new IOException("footer[4]=" + footer[4] + " expectedSize[0]="
                                  + (expectedSizeVal & 0xFF));
        if (!(footer[5] == (byte)(expectedSizeVal >>> 8)))
            throw new IOException("footer[5]=" + footer[5] + " expectedSize[1]="
                                  + ((expectedSizeVal >>> 8) & 0xFF));
        if (!(footer[6] == (byte)(expectedSizeVal >>> 16)))
            throw new IOException("footer[6]=" + footer[6] + " expectedSize[2]="
                                  + ((expectedSizeVal >>> 16) & 0xFF));
        if (!(footer[7] == (byte)(expectedSizeVal >>> 24)))
            throw new IOException("footer[7]=" + footer[7] + " expectedSize[3]="
                                  + ((expectedSizeVal >>> 24) & 0xFF));
    }
    
    /**
     * Make sure the header is valid, throwing an IOException if its b0rked.
     */
    private void verifyHeader() throws IOException {
        int c = in.read();
        if (c != 0x1F) throw new IOException("First magic byte was wrong [" + c + "]");
        c = in.read();
        if (c != 0x8B) throw new IOException("Second magic byte was wrong [" + c + "]");
        c = in.read();
        if (c != 0x08) throw new IOException("Compression format is invalid [" + c + "]");
        
        int flags = in.read();
        
        // snag (and ignore) the MTIME
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME0 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME1 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME2 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME3 [" + c + "]");
        
        c = in.read();
        if ( (c != 0x00) && (c != 0x02) && (c != 0x04) ) 
            throw new IOException("Invalid extended flags [" + c + "]");
        
        c = in.read(); // ignore creator OS
        
        // handle flags...
        if (0 != (flags & (1<<5))) {
            // extra header, read and ignore
            int _len = 0;
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the extra header");
            _len = c;
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the extra header");
            _len += (c << 8);
            
            // now skip that data
            for (int i = 0; i < _len; i++) {
                c = in.read();
                if (c == -1) throw new IOException("EOF reading the extra header's body");
            }
        }
        
        if (0 != (flags & (1 << 4))) {
            // ignore the name
            c = in.read();
            while (c != 0) {
                if (c == -1) throw new IOException("EOF reading the name");
                c = in.read();
            }
        }
        
        if (0 != (flags & (1 << 3))) {
            // ignore the comment
            c = in.read();
            while (c != 0) {
                if (c == -1) throw new IOException("EOF reading the comment");
                c = in.read();
            }
        }
        
        if (0 != (flags & (1 << 6))) {
            // ignore the header CRC16 (we still check the body CRC32)
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the CRC16");
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the CRC16");
        }
    }
    
/******
    public static void main(String args[]) {
        for (int i = 129; i < 64*1024; i++) {
            if (!test(i)) return;
        }
        
        byte orig[] = "ho ho ho, merry christmas".getBytes();
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64);
            java.util.zip.GZIPOutputStream o = new java.util.zip.GZIPOutputStream(baos);
            o.write(orig);
            o.finish();
            o.flush();
            o.close();
            byte compressed[] = baos.toByteArray();
            
            ResettableGZIPInputStream i = new ResettableGZIPInputStream();
            i.initialize(new ByteArrayInputStream(compressed));
            byte readBuf[] = new byte[128];
            int read = i.read(readBuf);
            if (read != orig.length)
                throw new RuntimeException("read=" + read);
            for (int j = 0; j < read; j++)
                if (readBuf[j] != orig[j])
                    throw new RuntimeException("wtf, j=" + j + " readBuf=" + readBuf[j] + " orig=" + orig[j]);
            boolean ok = (-1 == i.read());
            if (!ok) throw new RuntimeException("wtf, not EOF after the data?");
            System.out.println("Match ok");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static boolean test(int size) {
        byte b[] = new byte[size];
        new java.util.Random().nextBytes(b);
        try { 
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(size);
            java.util.zip.GZIPOutputStream o = new java.util.zip.GZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ResettableGZIPInputStream in = new ResettableGZIPInputStream(new ByteArrayInputStream(compressed));
            java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream(size);
            byte rbuf[] = new byte[512];
            while (true) {
                int read = in.read(rbuf);
                if (read == -1)
                    break;
                baos2.write(rbuf, 0, read);
            }
            byte rv[] = baos2.toByteArray();
            if (rv.length != b.length)
                throw new RuntimeException("read length: " + rv.length + " expected: " + b.length);
            
            if (!net.i2p.data.DataHelper.eq(rv, 0, b, 0, b.length)) {
                throw new RuntimeException("foo, read=" + rv.length);
            } else {
                System.out.println("match, w00t @ " + size);
                return true;
            }
        } catch (Exception e) { 
            System.out.println("Error dealing with size=" + size + ": " + e.getMessage());
            e.printStackTrace(); 
            return false;
        }
    }
******/
}
