package dev.spm.core.parser;

import dev.spm.core.format.common.BoundedJson;
import java.io.IOException;

/** Raw YSM authoring accepts Java/C comments via the original Gson.fromJson entrypoint.
 * Remove only those comments; the common JSON reader still decides every other syntax rule. */
final class RawYsmJson {
    private RawYsmJson(){}

    static byte[] withoutComments(byte[] source,BoundedJson.Quota quota)throws IOException {
        quota.cancellation.check();
        if(source.length>quota.policy.maxInputBytes())throw new IOException("Raw JSON input limit exceeded");
        validUtf8(source,quota);
        // Charge the actual output copy before allocating; it never expands the input.
        quota.charge(source.length);byte[] output=source.clone();
        boolean quoted=false;int nextCheck=0;
        for(int i=0;i<output.length;i++){
            if(i>=nextCheck){quota.cancellation.check();nextCheck=i+1024;}
            int current=output[i]&255;
            if(quoted){
                if(current=='\\')i++;
                else if(current=='"')quoted=false;
                continue;
            }
            if(current=='"'){quoted=true;continue;}
            if(current!='/'||i+1>=output.length)continue;
            if(output[i+1]=='/'){
                output[i++]=' ';output[i]=' ';
                while(i+1<output.length&&output[i+1]!='\r'&&output[i+1]!='\n'){
                    if(++i>=nextCheck){quota.cancellation.check();nextCheck=i+1024;}
                    output[i]=' ';
                }
            }else if(output[i+1]=='*'){
                output[i++]=' ';output[i]=' ';boolean closed=false;
                while(++i<output.length){
                    if(i>=nextCheck){quota.cancellation.check();nextCheck=i+1024;}
                    if(output[i]=='*'&&i+1<output.length&&output[i+1]=='/'){
                        output[i++]=' ';output[i]=' ';closed=true;break;
                    }
                    if(output[i]!='\r'&&output[i]!='\n')output[i]=' ';
                }
                if(!closed)throw new IOException("Unterminated raw JSON comment");
            }
        }
        quota.cancellation.check();return output;
    }

    /** Validate even comment contents, so comment removal cannot conceal malformed UTF-8. */
    private static void validUtf8(byte[] source,BoundedJson.Quota quota)throws IOException {
        int nextCheck=0;
        for(int i=0;i<source.length;i++){
            if(i>=nextCheck){quota.cancellation.check();nextCheck=i+1024;}
            int first=source[i]&255;if(first<128)continue;
            int count,lower=128,upper=191;
            if(first>=0xc2&&first<=0xdf)count=1;
            else if(first>=0xe0&&first<=0xef){count=2;if(first==0xe0)lower=0xa0;else if(first==0xed)upper=0x9f;}
            else if(first>=0xf0&&first<=0xf4){count=3;if(first==0xf0)lower=0x90;else if(first==0xf4)upper=0x8f;}
            else throw new IOException("Malformed UTF-8 in raw JSON");
            if(count>source.length-i-1)throw new IOException("Truncated UTF-8 in raw JSON");
            for(int offset=1;offset<=count;offset++){
                int next=source[i+offset]&255;
                if(next<(offset==1?lower:128)||next>(offset==1?upper:191))throw new IOException("Malformed UTF-8 in raw JSON");
            }
            i+=count;
        }
    }
}
