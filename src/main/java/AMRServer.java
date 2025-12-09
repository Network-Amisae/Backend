import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

// [NEW] WebSocket 관련 임포트
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

public class AMRServer {

    private static final int TCP_PORT = 8888; // AMR DCC 전용 포트
    private static final int WS_PORT = 8889;  // [NEW] 웹 프론트엔드용 포트
    private static final String SERVER_ID = "DCC_SERVER";
    private static final String SCENARIO_FILE = "amr_scenario.json";

    // 시간 포맷터 (콘솔용 / JSON패킷용)
    private static final DateTimeFormatter CONSOLE_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    // [NEW] 웹소켓 서버 인스턴스
    private static SimpleWebSocketServer wsServer;

    public static void main(String[] args) {
        log(">> [" + SERVER_ID + "] 시스템 시작 중...");

        // 1. [NEW] 웹소켓 서버 시작
        wsServer = new SimpleWebSocketServer(WS_PORT);
        wsServer.start();
        log(">> [Web] 웹소켓 방송 서버 시작됨 (Port: " + WS_PORT + ")");

        // 2. 시나리오 실행 스레드 시작
        new Thread(new ScenarioRunner(SCENARIO_FILE)).start();

        // 3. TCP 소켓 서버 시작
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            log(">> [TCP] AMR 연결 대기 중 (Port: " + TCP_PORT + ")");
            while (true) {
                new AMRClientHandler(serverSocket.accept()).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // [NEW] 로그 헬퍼 메서드
    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(CONSOLE_TIME_FMT) + "] " + msg);
    }

    // --- [NEW] 웹소켓 서버 클래스 ---
    static class SimpleWebSocketServer extends WebSocketServer {
        public SimpleWebSocketServer(int port) {
            super(new InetSocketAddress(port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            log(">> [Web] 대시보드 접속됨: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // log(">> [Web] 접속 해제");
        }

        @Override
        public void onMessage(WebSocket conn, String message) { }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ex.printStackTrace();
        }

        @Override
        public void onStart() { }
    }

    // --- 시나리오 실행기 ---
    static class ScenarioRunner implements Runnable {
        private String filePath;

        public ScenarioRunner(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try {
                log(">> [Scenario] 5초 후 시나리오를 시작합니다. (AMR 접속 대기)");
                Thread.sleep(5000);

                File file = new File(filePath);
                if (!file.exists()) {
                    log(">> [Scenario Error] 파일이 없습니다: " + filePath);
                    return;
                }

                String content = new String(Files.readAllBytes(Paths.get(filePath)));
                JSONArray scenarios = new JSONArray(content);
                log(">> [Scenario] 로드 완료 (" + scenarios.length() + " steps)");

                long startTime = System.currentTimeMillis();

                for (int i = 0; i < scenarios.length(); i++) {
                    JSONObject step = scenarios.getJSONObject(i);
                    long offset = step.getLong("time_offset_ms");

                    long currentTime = System.currentTimeMillis() - startTime;
                    long waitTime = offset - currentTime;

                    if (waitTime > 0) Thread.sleep(waitTime);

                    executeScenarioStep(step);
                }
                log(">> [Scenario] 모든 시나리오 종료.");

            } catch (Exception e) {
                log(">> [Scenario Runtime Error]");
                e.printStackTrace();
            }
        }

        private void executeScenarioStep(JSONObject step) {
            String targetId = step.getString("target_id");
            String command = step.getString("command");
            String desc = step.optString("description", "Mission Assigned");

            JSONObject packet = new JSONObject();

            JSONObject header = new JSONObject();
            header.put("type", "COMMAND");
            header.put("sender_id", SERVER_ID);
            header.put("receiver_id", targetId);
            header.put("timestamp", ZonedDateTime.now().format(ISO_FORMATTER));
            header.put("log_text", "[지시] " + desc);
            packet.put("header", header);

            JSONObject body = new JSONObject();
            body.put("task_id", step.optString("task_id", "AUTO_" + System.currentTimeMillis() % 1000));
            body.put("command", command);
            if (step.has("payload")) {
                body.put("payload", step.getJSONObject("payload"));
            } else {
                body.put("payload", new JSONObject());
            }
            packet.put("body", body);

            String jsonStr = packet.toString();

            // 1. TCP 전송
            PrintWriter writer = clients.get(targetId);
            if (writer != null) {
                writer.println(jsonStr);
                log(">> [Scenario -> " + targetId + "] Mission: " + desc);
            } else {
                log(">> [Scenario Error] 타겟 미접속: " + targetId);
            }

            // 2. [NEW] 웹소켓 브로드캐스트
            if (wsServer != null) {
                wsServer.broadcast(jsonStr);
            }
        }
    }

    // --- AMR 클라이언트 핸들러 ---
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
                // 접속 종료
            } finally {
                if (clientID != null) {
                    clients.remove(clientID);
                    log(">> [접속 종료] " + clientID);
                }
            }
        }

        private void processPacket(String jsonStr) {
            try {
                // 1. [NEW] 들어온 패킷을 그대로 웹소켓으로 중계 (Broadcast)
                if (wsServer != null) {
                    wsServer.broadcast(jsonStr);
                }

                // 2. 패킷 파싱
                JSONObject root = new JSONObject(jsonStr);
                JSONObject header = root.getJSONObject("header");
                JSONObject body = root.getJSONObject("body");

                String type = header.getString("type");
                String sender = header.getString("sender_id");

                if (clientID == null) {
                    clientID = sender;
                    clients.put(clientID, out);
                    log(">> [접속] " + clientID + " 연결됨.");
                }

                log("<< [" + sender + " - " + type + "]");

                switch (type) {
                    case "STATUS":
                        handleStatus(sender, body);
                        break;
                    case "ACK":
                        handleAck(sender, body);
                        break;
                    case "LOG":
                        handleLog(sender, body);
                        break;
                    default:
                        log("   [WARNING] Unknown Type: " + type);
                }

            } catch (Exception e) {
                log("<< [ERROR] Parsing Failed: " + e.getMessage());
            }
        }

        private void handleStatus(String sender, JSONObject body) {
            String deviceType = body.optString("device_type");
            String mode = body.optString("mode");

            if ("AMR".equals(deviceType)) {
                log("   - STATUS: " + sender + " 모드: " + mode);
            } else if ("CELL".equals(deviceType)) {
                // [개선] CELL인 경우 경고 대신 정상 로그 출력
                log("   - [Cell Report] " + sender + " 현재 상태: " + mode);
            } else {
                log("   - WARNING: Unknown Device (" + deviceType + ")");
            }
        }

        private void handleAck(String sender, JSONObject body) {
            String taskId = body.optString("task_id", "N/A");
            String command = body.optString("command", "ACK");
            log("   - ACK: Task " + taskId + " 완료/도착 (" + command + ")");
        }

        private void handleLog(String sender, JSONObject body) {
            String text = body.optString("message_text", "");
            log("   - LOG: " + text);
        }
    }
}