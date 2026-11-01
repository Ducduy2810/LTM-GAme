import vn.game.db.*;
import vn.game.games.*;
import vn.game.server.*;
import vn.game.util.JsonUtil;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Reproducible loopback measurements, always with a fresh isolated database. */
public final class NetworkBenchmark {
    record Sample(String protocol,int concurrency,int worker,int sequence,double milliseconds,boolean success){}
    static final int REQUESTS=30;
    static final int[] LEVELS={1,4,16,32};
    static final List<Sample> raw=new CopyOnWriteArrayList<>();
    static final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    interface Operation{void run()throws Exception;}
    static Map<String,Object> batch(String kind,int concurrency,List<Operation> operations)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready=new CountDownLatch(concurrency),start=new CountDownLatch(1);
        List<Future<?>> work=new ArrayList<>();
        try{
            for(int worker=0;worker<concurrency;worker++){
                int id=worker;Operation operation=operations.get(worker);
                work.add(pool.submit(()->{ready.countDown();start.await();for(int sequence=0;sequence<REQUESTS;sequence++){
                    long before=System.nanoTime();boolean success=true;
                    try{operation.run();}catch(Exception e){success=false;}
                    raw.add(new Sample(kind,concurrency,id,sequence,(System.nanoTime()-before)/1e6,success));
                }return null;}));
            }
            if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Workers failed to start");
            long before=System.nanoTime();start.countDown();for(Future<?> job:work)job.get(120,TimeUnit.SECONDS);
            double elapsed=(System.nanoTime()-before)/1e9;
            List<Sample> samples=raw.stream().filter(s->s.protocol.equals(kind)&&s.concurrency==concurrency).toList();
            double[] sorted=samples.stream().mapToDouble(Sample::milliseconds).sorted().toArray();
            Map<String,Object> row=new LinkedHashMap<>();row.put("protocol",kind);row.put("concurrency",concurrency);row.put("requests",samples.size());
            row.put("errors",samples.stream().filter(s->!s.success).count());row.put("elapsed_seconds",elapsed);
            row.put("mean_ms",Arrays.stream(sorted).average().orElseThrow());row.put("median_ms",sorted[(sorted.length-1)/2]);
            row.put("p95_ms",sorted[(int)Math.ceil(sorted.length*.95)-1]);row.put("max_ms",sorted[sorted.length-1]);
            row.put("completed_per_second",samples.stream().filter(Sample::success).count()/elapsed);return row;
        }finally{start.countDown();pool.shutdownNow();}
    }
    static final class Peer implements WebSocket.Listener,AutoCloseable{
        final BlockingQueue<String> rooms=new LinkedBlockingQueue<>();final StringBuilder fragment=new StringBuilder();WebSocket socket;
        public void onOpen(WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){
            fragment.append(text);if(last){String packet=fragment.toString();fragment.setLength(0);if("room_list".equals(JsonUtil.parseObject(packet).get("type")))rooms.add(packet);}ws.request(1);return CompletableFuture.completedFuture(null);
        }
        public void onError(WebSocket ws,Throwable error){rooms.offer("ERROR");}
        void request()throws Exception{socket.sendText("{\"type\":\"get_rooms\"}",true).get(10,TimeUnit.SECONDS);String packet=rooms.poll(10,TimeUnit.SECONDS);if(packet==null||packet.equals("ERROR"))throw new IOException("No room_list response");}
        public void close(){if(socket!=null)socket.abort();}
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".network-benchmark")))throw new IllegalStateException("Use benchmark_network.ps1; never run against the real database");
        if(args.length!=2)throw new IllegalArgumentException("public directory and output directory required");
        Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        DatabaseManager db=DatabaseManager.getInstance();db.init();UserRepository users=new UserRepository(db);SessionManager sessions=new SessionManager();
        List<String> cookies=new ArrayList<>();for(int i=0;i<32;i++){String name="bench_"+i;users.register(name,name+"@example.com","BenchmarkOnly123!");cookies.add("session_id="+sessions.createSession(name));}
        List<Map<String,Object>> results=new ArrayList<>();
        try(ArenaGame arena=new ArenaGame(users);WheelGame wheel=new WheelGame(users);SurvivalGame survival=new SurvivalGame(users)){
            HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),users,sessions,arena,wheel,survival);
            Thread serverThread=new Thread(()->{try{server.start();}catch(IOException e){e.printStackTrace();}},"benchmark-server");serverThread.setDaemon(true);serverThread.start();
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(server.getPort()==0&&System.nanoTime()<deadline)Thread.sleep(10);
                if(server.getPort()==0)throw new IllegalStateException("Server did not bind");
                String base="http://127.0.0.1:"+server.getPort();
                for(int concurrency:LEVELS){
                    List<Operation> operations=new ArrayList<>();
                    for(int i=0;i<concurrency;i++){
                        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/auth/me")).header("Cookie",cookies.get(i)).timeout(Duration.ofSeconds(10)).GET().build();
                        operations.add(()->{HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200||!Boolean.TRUE.equals(JsonUtil.parseObject(response.body()).get("loggedIn")))throw new IOException("Invalid authenticated HTTP response");});
                    }
                    for(Operation op:operations)for(int warm=0;warm<2;warm++)op.run();
                    results.add(batch("HTTP /auth/me",concurrency,operations));
                }
                for(int concurrency:LEVELS){
                    List<Peer> peers=new ArrayList<>();List<Operation> operations=new ArrayList<>();
                    try{
                        for(int i=0;i<concurrency;i++){
                            Peer peer=new Peer();peer.socket=http.newWebSocketBuilder().header("Cookie",cookies.get(i)).connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create("ws://127.0.0.1:"+server.getPort()+"/ws"),peer).get(10,TimeUnit.SECONDS);
                            peers.add(peer);peer.request();peer.request();operations.add(peer::request);
                        }
                        results.add(batch("WebSocket get_rooms",concurrency,operations));
                    }finally{peers.forEach(Peer::close);}
                    Thread.sleep(150);
                }
                Map<String,Object> report=new LinkedHashMap<>();report.put("measured_at_utc",Instant.now().toString());report.put("transport","loopback 127.0.0.1, no ngrok, no TLS");
                report.put("java_version",System.getProperty("java.version"));report.put("java_vendor",System.getProperty("java.vendor"));report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
                report.put("logical_processors",Runtime.getRuntime().availableProcessors());report.put("requests_per_client",REQUESTS);report.put("warmup_per_client_per_level",2);report.put("results",results);
                Files.writeString(output.resolve("network-benchmark.json"),JsonUtil.toJson(report)+"\n",StandardCharsets.UTF_8);
                StringBuilder csv=new StringBuilder("protocol,concurrency,worker,sequence,latency_ms,success\n");for(Sample s:raw)csv.append(String.format(Locale.ROOT,"%s,%d,%d,%d,%.6f,%s%n",s.protocol,s.concurrency,s.worker,s.sequence,s.milliseconds,s.success));
                Files.writeString(output.resolve("network-samples.csv"),csv.toString(),StandardCharsets.UTF_8);
                System.out.println(JsonUtil.toJson(report));
                if(raw.stream().anyMatch(s->!s.success))throw new AssertionError("Some benchmark operations failed; keep errors visible in the results");
            }finally{server.stop();serverThread.join(3000);}
        }finally{db.close();}
    }
}
