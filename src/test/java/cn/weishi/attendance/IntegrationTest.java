package cn.weishi.attendance;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.opencv.core.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties={"app.auth-enabled=true","app.face.recognition-only=false","app.admin-password=test-admin-password-2026","app.feature-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.local-password=test-local-password","app.data-dir=./target/integration-data","app.camera.enabled=false","app.cloud.enabled=false"})
@AutoConfigureMockMvc
class IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired FaceEngine engine;
    @Autowired LocalStore local;
    @Autowired FeatureCodec codec;
    @Test void unauthenticatedEndpointsRequireLogin() throws Exception{
        for(String path:new String[]{"/api/status","/api/csrf","/api/employees","/api/events","/api/camera/frame.jpg","/api/camera/stream.mjpg"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        }
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }
    @Test void configuredAdministratorCanAuthenticate() throws Exception {
        MockHttpSession session=login();
        mvc.perform(get("/api/status").session(session)).andExpect(status().isOk());
    }
    @Test void staleBasicCredentialsDoNotOverrideLoggedInSession() throws Exception {
        MockHttpSession session=login();
        mvc.perform(get("/api/status").session(session).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic("admin","outdated-password")))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/camera/frame.jpg").session(session)).andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/camera/stream.mjpg").session(session)).andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("WWW-Authenticate"));
    }
    @Test void wrongPasswordAndLogoutDoNotCreateBrowserChallenges() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","wrong-password"))
                .andExpect(redirectedUrl("/login?error")).andExpect(header().doesNotExist("WWW-Authenticate"));
        MockHttpSession session=login();
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
    }
    private MockHttpSession login() throws Exception {
        MvcResult result=mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","test-admin-password-2026"))
                .andExpect(redirectedUrl("/")).andReturn();return (MockHttpSession)result.getRequest().getSession(false);
    }
    @Test @WithMockUser void statusWorksAndMutationRequiresCsrf() throws Exception{
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.modelVersion").value(FeatureCodec.MODEL_VERSION));
        mvc.perform(post("/api/recognition/false")).andExpect(status().isForbidden());
        mvc.perform(post("/api/recognition/false").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/recognition/true").with(csrf())).andExpect(status().isOk());
    }
    @Test void modelRunsAndBlankImageDoesNotMatch(){Mat image=new Mat(480,640,CvType.CV_8UC3,new Scalar(100,100,100));try{FaceEngine.Result result=engine.analyze(image,new Records.Snapshot(),true);assertEquals(0,result.faceCount);assertFalse(result.accepted);}finally{image.release();}}
    @Test void optionalRealPhotoExtractsFeaturesAndRejectsAmbiguousEmployees(){
        String path=System.getProperty("face.testImage","");
        org.junit.jupiter.api.Assumptions.assumeTrue(!path.isEmpty(),"提供 -Dface.testImage=图片路径 可运行真实照片模型冒烟测试");
        Mat image=org.opencv.imgcodecs.Imgcodecs.imread(path);Mat changed=new Mat();
        try{
            assertFalse(image.empty());FaceEngine.Result enrollment=engine.analyze(image,new Records.Snapshot(),false);
            assertTrue(enrollment.accepted,enrollment.message);assertEquals(128,enrollment.feature.length);
            Records.Snapshot snapshot=new Records.Snapshot();snapshot.employees.add(new Records.Employee("PHOTO01","照片测试",true));
            Records.Template template=new Records.Template();template.employeeId="PHOTO01";template.vector=enrollment.feature;snapshot.templates.add(template);
            image.convertTo(changed,-1,1.03,2);FaceEngine.Result result=engine.analyze(changed,snapshot,true);
            assertTrue(result.accepted,result.message);assertEquals("PHOTO01",result.employeeId);assertTrue(result.similarity>0.9);
            snapshot.employees.add(new Records.Employee("PHOTO02","重叠模板",true));Records.Template duplicate=new Records.Template();duplicate.employeeId="PHOTO02";duplicate.vector=enrollment.feature;snapshot.templates.add(duplicate);
            assertFalse(engine.analyze(image,snapshot,true).accepted);
        }finally{image.release();changed.release();}
    }
    @Test void cloudSnapshotAndIdempotentUploadAndExpiry(){
        String url="jdbc:h2:mem:cloud-integration;MODE=MySQL;DB_CLOSE_DELAY=-1";
        HikariConfig hc=new HikariConfig();hc.setJdbcUrl(url);hc.setUsername("sa");hc.setPassword("test");
        try(HikariDataSource source=new HikariDataSource(hc)){
            JdbcTemplate jdbc=new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE fa_employee(employee_id VARCHAR(64) PRIMARY KEY,employee_name VARCHAR(100),active BOOLEAN)");
            jdbc.execute("CREATE TABLE fa_face_template(template_id VARCHAR(36) PRIMARY KEY,employee_id VARCHAR(64),model_version VARCHAR(64),feature BLOB)");
            jdbc.execute("CREATE TABLE fa_attendance_event(event_id VARCHAR(36) PRIMARY KEY,employee_id VARCHAR(64),employee_name VARCHAR(100),device_id VARCHAR(64),occurred_at VARCHAR(40),punch_type VARCHAR(20),similarity DOUBLE)");
            AppConfig config=new AppConfig(new MockEnvironment().withProperty("app.cloud.enabled","true").withProperty("app.cloud.url",url).withProperty("app.cloud.username","sa").withProperty("app.cloud.password","test").withProperty("app.face.cache-ttl-hours","24"));
            CloudStore cloud=new CloudStore(config);
            try{
                float[] feature=new float[128];feature[0]=1;cloud.enroll("CLOUD01","云端员工",codec.encrypt(feature));
                Gallery gallery=new Gallery(local,cloud,codec,config);gallery.refresh();assertTrue(gallery.usable());assertEquals(1,gallery.snapshot().templates.size());
                Records.Event event=new Records.Event();event.eventId="idempotent-test";event.employeeId="CLOUD01";event.employeeName="云端员工";event.deviceId="test";event.occurredAt=Instant.now().toString();event.punchType="PUNCH";event.similarity=0.8;
                cloud.upload(event);cloud.upload(event);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fa_attendance_event",Integer.class));
                cloud.disable("CLOUD01");gallery.refresh();assertEquals(0,gallery.snapshot().templates.size());
                gallery.snapshot().fetchedAt=System.currentTimeMillis()-25*3600000L;assertFalse(gallery.usable());
            }finally{cloud.close();}
        }
    }
    @Test void thirdPartyBusinessFailureDoesNotPreventNextEmployee() throws Exception{
        String suffix=Long.toHexString(System.nanoTime());String bad="BAD"+suffix,good="GOOD"+suffix;
        AtomicInteger calls=new AtomicInteger();ObjectMapper mapper=new ObjectMapper();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/attendance",exchange->{try{byte[] request=read(exchange.getRequestBody());String id=mapper.readTree(request).path("employeeId").asText();String eventId=mapper.readTree(request).path("eventId").asText();assertEquals(eventId,exchange.getRequestHeaders().getFirst("Idempotency-Key"));calls.incrementAndGet();byte[] reply=(id.equals(bad)?"{\"success\":false}":"{\"success\":true}").getBytes("UTF-8");exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);}finally{exchange.close();}});server.start();
        try{
            Records.Event failed=local.record(bad,"失败测试",0.8);Records.Event accepted=local.record(good,"成功测试",0.8);
            AppConfig config=new AppConfig(new MockEnvironment().withProperty("app.face.recognition-only","false").withProperty("app.cloud.enabled","false").withProperty("app.third-party.enabled","true").withProperty("app.third-party.url","http://127.0.0.1:"+server.getAddress().getPort()+"/attendance"));
            CloudStore cloud=new CloudStore(config);SyncService service=new SyncService(local,cloud,config,mapper);service.remoteTick();
            Records.Event a=local.recent().stream().filter(e->e.eventId.equals(failed.eventId)).findFirst().get();Records.Event b=local.recent().stream().filter(e->e.eventId.equals(accepted.eventId)).findFirst().get();
            assertEquals("PENDING",a.remoteStatus);assertEquals(1,a.retryCount);assertEquals("SYNCED",b.remoteStatus);assertTrue(calls.get()>=2);
        }finally{server.stop(0);}
    }
    private static byte[] read(java.io.InputStream input)throws java.io.IOException{java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}
}
