import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class AGVServer {

    private static final int PORT = 9001;
    // 클라이언트 소켓 저장소
    private static Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println(">> [ACS Server] 포트 " + PORT + "에서 시작됨...");

        // 1. 시나리오 실행 스레드 시작
        // (주의: AGV가 접속할 시간을 주기 위해 5초 뒤에 시나리오 시작)
        new Thread(new ScenarioRunner("agv_scenario.json")).start();

        // 2. 소켓 서버 시작
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                new ClientHandler(serverSocket.accept()).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- [New] 시나리오 실행기 ---
    static class ScenarioRunner implements Runnable {
        private String filePath;

        public ScenarioRunner(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try {
                // 파일이 없거나 AGV 접속 대기를 위해 잠시 쉼
                System.out.println(">> [Scenario] 10초 후 시나리오를 시작합니다. (AGV 접속 대기)");
                Thread.sleep(10000);

                File file = new File(filePath);
                if (!file.exists()) {
                    System.out.println(">> [Scenario] 파일이 없습니다: " + filePath);
                    return;
                }

                // JSON 파일 읽기
                String content = new String(Files.readAllBytes(Paths.get(filePath)));
                JSONArray scenarios = new JSONArray(content);
                System.out.println(">> [Scenario] 로드 완료 (" + scenarios.length() + " steps)");

                long startTime = System.currentTimeMillis();

                for (int i = 0; i < scenarios.length(); i++) {
                    JSONObject step = scenarios.getJSONObject(i);
                    long offset = step.getLong("time_offset_ms");

                    // 현재 시간과 목표 시간의 차이만큼 대기
                    long currentTime = System.currentTimeMillis() - startTime;
                    long waitTime = offset - currentTime;

                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }

                    // 명령 전송 실행
                    executeScenarioStep(step);
                }
                System.out.println(">> [Scenario] 모든 시나리오 종료.");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void executeScenarioStep(JSONObject step) {
            String targetId = step.getString("target_id");
            String command = step.getString("command");
            String desc = step.optString("description", "Unknown Command");

            // 우리가 정의한 통신 프로토콜(Header/Body)로 변환
            JSONObject packet = new JSONObject();

            // Header 생성
            JSONObject header = new JSONObject();
            header.put("type", "COMMAND");
            header.put("sender_id", "ACS_SERVER");
            header.put("receiver_id", targetId);
            header.put("timestamp", java.time.LocalDateTime.now().toString());
            header.put("log_text", "[지시] " + desc); // 채팅창에 뜰 메시지
            packet.put("header", header);

            // Body 생성
            JSONObject body = new JSONObject();
            body.put("task_id", step.optString("task_id", "AUTO_TASK"));
            body.put("command", command);
            if (step.has("payload")) {
                body.put("payload", step.getJSONObject("payload"));
            }
            packet.put("body", body);

            // 전송
            PrintWriter writer = clients.get(targetId);
            if (writer != null) {
                writer.println(packet.toString());
                System.out.println(">> [Scenario -> " + targetId + "] " + desc);
            } else {
                System.out.println(">> [Scenario Error] 타겟이 접속해 있지 않습니다: " + targetId);
            }
        }
    }

    // --- 기존 ClientHandler (유지) ---
    private static class ClientHandler extends Thread {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String clientID = null;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while ((line = in.readLine()) != null) {
                    processPacket(line);
                }
            } catch (IOException e) {
                // 접속 끊김 처리
            } finally {
                if (clientID != null) {
                    clients.remove(clientID);
                    System.out.println(">> [접속 종료] " + clientID);
                }
            }
        }

        private void processPacket(String jsonStr) {
            try {
                JSONObject root = new JSONObject(jsonStr);
                JSONObject header = root.getJSONObject("header");
                String sender = header.getString("sender_id");

                // 최초 접속 시 등록
                if (clientID == null) {
                    clientID = sender;
                    clients.put(clientID, out);
                    System.out.println(">> [접속] " + clientID + " 연결됨.");
                }

                // 로그 출력
                System.out.println("<< [" + sender + "] " + header.getString("log_text"));

            } catch (Exception e) {
                System.out.println("Invalid Packet: " + e.getMessage());
            }
        }
    }
}