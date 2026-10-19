package cn.weishi.attendance;

import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class VideoTest {
    @Test void latestFrameIsSharedAndSlowConsumersSkipBacklog()throws Exception {
        PreviewFrames frames=new PreviewFrames();byte[] first={1},second={2},third={3};
        frames.publish(System.currentTimeMillis(),first);long previous=frames.current().sequence;
        frames.publish(System.currentTimeMillis(),second);frames.publish(System.currentTimeMillis(),third);
        PreviewFrames.EncodedFrame a=frames.awaitAfter(previous,10),b=frames.awaitAfter(previous,10);
        assertSame(a,b);assertSame(third,a.jpeg);assertEquals(previous+2,a.sequence);
        assertNull(frames.awaitAfter(a.sequence,10));frames.clear();assertNull(frames.current());
    }
    @Test void multipartStreamDeliversMoreThanPollingLimitAndDoesNotReplayOldFrames()throws Exception {
        PreviewFrames frames=new PreviewFrames();ByteArrayOutputStream output=new ByteArrayOutputStream();
        ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();ExecutorService reader=Executors.newSingleThreadExecutor();AtomicLong produced=new AtomicLong();
        frames.publish(System.currentTimeMillis(),new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});
        producer.scheduleAtFixedRate(()->{frames.publish(System.currentTimeMillis(),new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});produced.incrementAndGet();},40,40,TimeUnit.MILLISECONDS);
        long started=System.nanoTime();
        try {
            Future<?> result=reader.submit(()->{try{MjpegStream.write(frames,output,1100);}catch(IOException ex){throw new RuntimeException(ex);}});result.get(3,TimeUnit.SECONDS);
        }finally{producer.shutdownNow();reader.shutdownNow();}
        String wire=new String(output.toByteArray(),StandardCharsets.ISO_8859_1);int count=wire.split("Content-Type: image/jpeg",-1).length-1;
        double fps=count/((System.nanoTime()-started)/1000000000.0);
        assertTrue(count>=15,"Expected sustained stream, got "+count);assertTrue(count<=produced.get()+1);assertTrue(wire.contains("Content-Length: 4\r\n"));assertTrue(wire.contains("X-Captured-At: "));assertTrue(wire.endsWith("--frame--\r\n"));
        System.out.println("Synthetic MJPEG transport: "+count+" frames, "+String.format("%.1f",fps)+" FPS");
    }
    @Test void disconnectedClientAndStaleFramesEndWithoutBusyLoop()throws Exception {
        PreviewFrames frames=new PreviewFrames();frames.publish(System.currentTimeMillis()-5000,new byte[]{1});
        ByteArrayOutputStream output=new ByteArrayOutputStream();MjpegStream.write(frames,output,200);
        assertFalse(new String(output.toByteArray(),StandardCharsets.US_ASCII).contains("Content-Type: image/jpeg"));
        frames.publish(System.currentTimeMillis(),new byte[]{1});
        MjpegStream.write(frames,new OutputStream(){@Override public void write(int b)throws IOException{throw new IOException("client disconnected");}},200);
    }
    @Test void lowerPreviewQualityReducesPayloadAndRemainsDecodable(){
        nu.pattern.OpenCV.loadLocally();Mat image=new Mat(480,640,CvType.CV_8UC3);Core.randu(image,0,255);
        Mat decoded=new Mat();MatOfByte bytes=new MatOfByte();
        try {
            byte[] high=FaceEngine.jpeg(image,95),low=FaceEngine.jpeg(image,75);assertTrue(low.length<high.length*0.8);
            bytes.fromArray(low);decoded.release();decoded=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_COLOR);assertEquals(image.size(),decoded.size());
            System.out.println("Synthetic JPEG payload: quality 95="+high.length+", quality 75="+low.length+" bytes");
        }finally{image.release();decoded.release();bytes.release();}
    }
}
