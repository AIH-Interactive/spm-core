package dev.spm.core.parser;

import dev.spm.core.api.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Bounds and charges both growing backing capacity and the final encoded input snapshot. */
final class ParserInput {
    private ParserInput(){}
    static Snapshot read(ImportRequest request,ResourceKey key,ResourceResolver resolver) throws IOException {
        request.cancellation().check();
        Snapshot completed=null;
        try(InputStream input=resolver.open(key)) {
            if(input==null)throw new IOException("Resolver returned null");
            int limit=(int)Math.min(Integer.MAX_VALUE-8L,request.policy().maxInputBytes());
            ResourceBudget.Reservation credit=null;byte[] bytes=null;
            try {
                int capacity=Math.min(8192,limit),used=0;
                credit=request.budget().reserve(capacity);bytes=new byte[capacity];
                while(true) {
                    request.cancellation().check();
                    if(used==bytes.length) {
                        int next=input.read();if(next<0)break;
                        if(used==limit)throw new InputLimitException();
                        int larger=(int)Math.min(limit,Math.max((long)used+1,Math.max(1L,(long)bytes.length*2)));
                        var nextCredit=request.budget().reserve(larger);byte[] grown;
                        try{grown=Arrays.copyOf(bytes,larger);}catch(RuntimeException|Error failure){nextCredit.close();throw failure;}
                        credit.close();credit=nextCredit;bytes=grown;bytes[used++]=(byte)next;
                    } else {
                        int count=input.read(bytes,used,bytes.length-used);
                        if(count<0)break;
                        if(count==0){int one=input.read();if(one<0)break;bytes[used++]=(byte)one;}else used+=count;
                    }
                }
                request.cancellation().check();
                if(used!=bytes.length){
                    var exact=request.budget().reserve(used);byte[] copy;
                    try{copy=Arrays.copyOf(bytes,used);}catch(RuntimeException|Error failure){exact.close();throw failure;}
                    credit.close();credit=exact;bytes=copy;
                }
                completed=new Snapshot(bytes,credit);credit=null;return completed;
            } finally {if(credit!=null)credit.close();}
        }catch(IOException|RuntimeException|Error failure){if(completed!=null)completed.close();throw failure;}
    }
    static final class InputLimitException extends IOException {
        private static final long serialVersionUID=1L;
        InputLimitException(){super("Encoded input exceeds request limit");}
    }
    static final class Snapshot implements AutoCloseable {
        private byte[] bytes;
        private final ResourceBudget.Reservation credit;
        private int references=1;
        private boolean retired;
        Snapshot(byte[] bytes,ResourceBudget.Reservation credit){this.bytes=bytes;this.credit=credit;}
        synchronized byte[] bytes(){if(retired)throw new IllegalStateException("Source snapshot closed");return bytes;}
        synchronized AutoCloseable retain(){
            if(retired)throw new IllegalStateException("Source snapshot closed");
            if(references==Integer.MAX_VALUE)throw new IllegalStateException("Too many source leases");
            var returned=new java.util.concurrent.atomic.AtomicBoolean();
            AutoCloseable lease=()->{if(returned.compareAndSet(false,true))release();};references++;return lease;
        }
        private synchronized void release(){if(--references==0){bytes=null;credit.close();}}
        @Override public synchronized void close(){if(retired)return;retired=true;release();}
    }
}
