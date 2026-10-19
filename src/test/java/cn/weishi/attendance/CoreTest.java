package cn.weishi.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @TempDir Path directory;
    private AppConfig config(){return new AppConfig(new MockEnvironment().withProperty("app.feature-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").withProperty("app.local-password","local-password-for-test").withProperty("app.data-dir",directory.toString()).withProperty("app.face.dedup-seconds","60"));}
    @Test void encryptedFeaturesRoundTripAndRejectTampering(){
        FeatureCodec codec=new FeatureCodec(config());float[] feature=new float[128];feature[2]=1;
        byte[] encrypted=codec.encrypt(feature);assertArrayEquals(feature,codec.decrypt(encrypted));
        assertFalse(Arrays.equals(encrypted,codec.encrypt(feature)));encrypted[20]^=1;
        assertThrows(IllegalStateException.class,()->codec.decrypt(encrypted));
    }
    @Test void confirmationRequiresSameTrackAndConsecutiveIdentity(){
        ConfirmationGate gate=new ConfirmationGate();FaceEngine.Result result=new FaceEngine.Result();result.accepted=true;result.employeeId="E001";result.box=new int[]{10,10,100,100};
        assertFalse(gate.accept(result,1000,3));assertFalse(gate.accept(result,1300,3));assertTrue(gate.accept(result,1600,3));
        result.box=new int[]{300,10,100,100};assertFalse(gate.accept(result,1900,3));result.accepted=false;assertFalse(gate.accept(result,2200,3));
        result.accepted=true;assertFalse(gate.accept(result,2500,3));assertFalse(gate.accept(result,4500,3));
    }
    @Test void durableEventsDeduplicateAfterRestartAndFailureDoesNotBlockLaterEvents() throws Exception {
        Records.Event first;
        LocalStore local=new LocalStore(config());
        try{first=local.record("E001","员工一",0.8);assertNotNull(first);assertNull(local.record("E001","员工一",0.8));}
        finally{local.close();}
        local=new LocalStore(config());
        try{
            assertNull(local.record("E001","员工一",0.8));assertNotNull(local.record("E002","员工二",0.9));
            local.remoteFailed(first,"HTTP 400",true);assertEquals(1,local.remotePending().size());
            assertEquals("E002",local.remotePending().get(0).employeeId);assertEquals(1,local.retryFailed(first.eventId));assertEquals(2,local.remotePending().size());
        }finally{local.close();}
    }
}
