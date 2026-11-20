package org.matonos.store.fdroid;
import java.io.*;

public final class FdroidCatalogueWriterTest {
  public static void main(String[] args)throws Exception{
    File output=new File(args[0]);int count=75_000;
    try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output),java.nio.charset.StandardCharsets.UTF_8),64*1024)){
      FdroidCatalogueWriter.write(writer,"org.matonos.store.fdroid",1730000000L,out->{
        for(int i=0;i<count;i++){if(i>0)out.write(',');out.write("{\"id\":\"org.example.app"+i+"\",\"name\":\"Synthetic "+i+"\",\"summary\":\"large index\",\"repo\":\"stable\"}");}
      });
    }
    long ids=0;try(BufferedReader reader=new BufferedReader(new FileReader(output))){String line;while((line=reader.readLine())!=null){int at=0;while((at=line.indexOf("\"id\":",at))>=0){ids++;at+=5;}}}
    if(ids!=count)throw new AssertionError("expected "+count+" streamed rows, got "+ids);
    if(output.length()<count*50L)throw new AssertionError("catalogue output unexpectedly small: "+output.length());
    System.out.println("PASS: streamed "+count+" synthetic catalogue rows, bytes="+output.length());
  }
}
