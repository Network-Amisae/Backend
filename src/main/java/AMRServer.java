import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class AMRServer {

    private static final int PORT = 8888; // AMR DCC 전용 포트
    private static final String SERVER_ID = "DCC_SERVER"; // 서버 식별자
    private static final String SCENARIO_FILE = "amr_scenario.json";

    // 클라이언트 소켓 출력 스트림 저장소 (AMR_01 -> PrintWriter)
    private static Map<String, PrintWriter> clients = new ConcurrentHashMap<>();
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");


    public static void main(String[] args) {
        System.out.println(">> [" + SERVER_ID + "] 포트 " + PORT + "에서 시작됨...");

        // 1. 시나리오 실행 스레드 시작
        // (주의: AMR이 접속할 시간을 주기 위해 잠시 대기 후 시나리오 시작)
        new Thread(new ScenarioRunner(SCENARIO_FILE)).start();

        // 2. 소켓 서버 시작
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                // 클라이언트 연결 시 AMRClientHandler 스레드 생성 및 실행
                new AMRClientHandler(serverSocket.accept()).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==========================================================
    // 1. 시나리오 실행기 (COMMAND 전송 담당)
    // ==========================================================
    static class ScenarioRunner implements Runnable {
        private String filePath;

        public ScenarioRunner(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try {
                System.out.println(">> [Scenario] 5초 후 시나리오를 시작합니다. (AMR 접속 대기)");
                Thread.sleep(5000); // 5초 대기

                File file = new File(filePath);
                if (!file.exists()) {
                    System.out.println(">> [Scenario Error] 파일이 없습니다: " + filePath);
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
                System.err.println(">> [Scenario Runtime Error]");
                e.printStackTrace();
            }
        }

        private void executeScenarioStep(JSONObject step) {
            String targetId = step.getString("target_id");
            String command = step.getString("command");
            String desc = step.optString("description", "Mission Assigned");

            // 통신 프로토콜(Header/Body)로 변환
            JSONObject packet = new JSONObject();

            // Header 생성
            JSONObject header = new JSONObject();
            header.put("type", "COMMAND");
            header.put("sender_id", SERVER_ID);
            header.put("receiver_id", targetId);
            header.put("timestamp", ZonedDateTime.now().format(ISO_FORMATTER));
            // packet_id는 추가되지 않았으므로 임시로 log_text 사용
            header.put("log_text", "[지시] " + desc);
            packet.put("header", header);

            // Body 생성 (Type3: COMMAND)
            JSONObject body = new JSONObject();
            body.put("task_id", step.optString("task_id", "AUTO_TASK_" + System.currentTimeMillis() % 1000));
            body.put("command", command); // DELIVER_PART, MOVE_TO_BASE 등

            // payload 처리
            if (step.has("payload")) {
                body.put("payload", step.getJSONObject("payload"));
            } else {
                // 페이로드가 없더라도 빈 객체라도 넣어 JSON 구조를 유지 (선택 사항)
                body.put("payload", new JSONObject());
            }
            packet.put("body", body);

            // 전송
            PrintWriter writer = clients.get(targetId);
            if (writer != null) {
                writer.println(packet.toString());
                System.out.println(">> [Scenario -> " + targetId + "] Mission: " + desc);
            } else {
                System.out.println(">> [Scenario Error] 타겟(" + targetId + ")이 접속해 있지 않아 명령을 전송할 수 없습니다.");
            }
        }
    }

    // ==========================================================
    // 2. AMR 클라이언트 핸들러 (메시지 수신 담당)
    // ==========================================================
    // AMR DCC 서버 내부에 정의된 정적 내부 클래스
    private static class AMRClientHandler extends Thread {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String clientID = null;

        public AMRClientHandler(Socket socket) {
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
                // 접속 끊김이나 통신 오류
            } finally {
                if (clientID != null) {
                    clients.remove(clientID); // 전역 맵에서 제거
                    System.out.println(">> [접속 종료] " + clientID);
                }
                try {
                    socket.close();
                } catch (IOException e) {/* ignore */}
            }
        }

        private void processPacket(String jsonStr) {
            try {
                JSONObject root = new JSONObject(jsonStr);
                JSONObject header = root.getJSONObject("header");
                JSONObject body = root.getJSONObject("body");

                String type = header.getString("type");
                String sender = header.getString("sender_id");

                // 1. 최초 접속 시 등록
                if (clientID == null) {
                    clientID = sender;
                    clients.put(clientID, out);
                    System.out.println(">> [접속] " + clientID + " 연결됨.");
                }

                // 2. AMR 메시지 처리
                System.out.println("<< [" + sender + " - " + type + "]");

                switch (type) {
                    case "STATUS":
                        // Type1: STATUS (2.3B: 자율 위치 보고 - 활성/비활성)
                        handleStatus(sender, body);
                        break;
                    case "ACK":
                        // ACK (2.4: 도착 및 작업 준비 알림)
                        handleAck(sender, body);
                        break;
                    case "LOG":
                        // Type5: LOG
                        handleLog(sender, body);
                        break;
                    default:
                        System.out.println("   [WARNING] Unknown Type: " + type);
                }

            } catch (Exception e) {
                System.out.println("<< [ERROR] Invalid Packet or Parsing Failed: " + e.getMessage());
            }
        }

        // STATUS 메시지 처리 (Type1: 2.3B)
        private void handleStatus(String sender, JSONObject body) {
            String deviceType = body.optString("device_type");
            String mode = body.optString("mode");

            if ("AMR".equals(deviceType)) {
                System.out.printf("   - STATUS (2.3B): %s 모드: %s\n", sender, mode);
            } else {
                // AMR 서버이므로 다른 타입은 무시 또는 경고
                System.out.printf("   - WARNING: Received STATUS from non-AMR device (%s)\n", deviceType);
            }
        }

        // ACK 메시지 처리 (2.4)
        private void handleAck(String sender, JSONObject body) {
            String taskId = body.optString("task_id", "N/A");
            String command = body.optString("command", "ACK"); // 예: ARRIVED_AT_CELL_A
            System.out.printf("   - ACK (2.4): Task %s 완료/도착 보고 (%s)\n", taskId, command);
        }

        // LOG 메시지 처리 (Type5)
        private void handleLog(String sender, JSONObject body) {
            String level = body.optString("log_level", "INFO");
            String text = body.optString("message_text", "No Message");
            System.out.printf("   - LOG (%s): %s\n", level, text);
        }
    }
}