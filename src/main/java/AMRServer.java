import java.io.*;
import java.net.ServerSocket;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONTokener;

public class AMRServer {

    private static final int PORT = 8888;
    // amr의 출력 스트림을 저장
    // 키는 amr 식별 변호(AMR_01), 값은 해당 amr에게 데이터를 보내는 통로
    private static Map<String, PrintWriter> clientWriters = new ConcurrentHashMap<>();
    // 쓰레드 풀
    private static final ExecutorService pool = Executors.newFixedThreadPool(10);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    public static void main(String[] args) {
        System.out.println("AMRServer is running on port" + PORT + "...");

        // 서버가 amr에게 명령을 보낼 수 있는 쓰레드 시작
        startCommandThread("scenario.json");
        try(ServerSocket listener = new ServerSocket(PORT)) {
            while (true) {
                // 수락된 소켓을 ClientHandler 객체에 넣어 스레드 풀 실행
                pool.execute(new ClientHandler(listener.accept()));
            }
        }catch (IOException e) {
            e.printStackTrace();
        }finally {
            pool.shutdown();
        }
    }

    // 서버 명령 실행 쓰레드
    public static void startCommandThread(String scenarioFilePath) {
        try (FileReader reader = new FileReader(scenarioFilePath)) {
            // JSON 파일 로드
            JSONArray scenarioArray = new JSONArray(new JSONTokener(reader));
        }
    }
}

