import java.io.*;
import java.net.InetSocketAddress; // [NEW]
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;        // [NEW]
import java.time.format.DateTimeFormatter; // [NEW]
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

// [NEW] WebSocket 관련 임포트 (build.gradle 의존성 필요)
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

public class AGVServer {

    private static final int TCP_PORT = 9001; // AGV 통신용
    private static final int WS_PORT = 9002;  // 웹 프론트엔드 통신용

    // 시간 포맷터
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 클라이언트 소켓 저장소
    private static Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    // [NEW] 웹소켓 서버 인스턴스 (정적 변수로 공유)
    private static SimpleWebSocketServer wsServer;

    public static void main(String[] args) {
        log(">> [ACS Server] 시스템 시작 중...");

        // 1. [NEW] 웹소켓 서버 시작
        wsServer = new SimpleWebSocketServer(WS_PORT);
        wsServer.start();
        log(">> [Web] 웹소켓 방송 서버 시작됨 (Port: " + WS_PORT + ")");

        // 2. 시나리오 실행 스레드 시작 (10초 대기 후 시작)
        new Thread(new ScenarioRunner("agv_scenario.json")).start();

        // 3. TCP 소켓 서버 시작
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            log(">> [TCP] AGV 연결 대기 중 (Port: " + TCP_PORT + ")");
            while (true) {
                new ClientHandler(serverSocket.accept()).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 로그 헬퍼
    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME_FMT) + "] " + msg);
    }

    // --- [NEW] 웹소켓 서버 클래스 (내부 클래스) ---
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
            // log(">> [Web] 접속 해제"); // 너무 자주 뜨면 주석 처리
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // 웹에서 서버로 보내는 메시지는 일단 무시 (단방향 방송 목적)
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ex.printStackTrace();
        }

        @Override
        public void onStart() {
            // 시작 시 로그는 main에서 출력함
        }
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
                log(">> [Scenario] 10초 후 시나리오를 시작합니다. (AGV 접속 대기)");
                Thread.sleep(10000);

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
                e.printStackTrace();
            }
        }

        private void executeScenarioStep(JSONObject step) {
            String targetId = step.getString("target_id");
            String command = step.getString("command");
            String desc = step.optString("description", "Unknown Command");

            JSONObject packet = new JSONObject();

            JSONObject header = new JSONObject();
            header.put("type", "COMMAND");
            header.put("sender_id", "ACS_SERVER");
            header.put("receiver_id", targetId);
            header.put("timestamp", java.time.LocalDateTime.now().toString());
            header.put("log_text", "[지시] " + desc);
            packet.put("header", header);

            JSONObject body = new JSONObject();
            body.put("task_id", step.optString("task_id", "AUTO_TASK"));
            body.put("command", command);
            if (step.has("payload")) {
                body.put("payload", step.getJSONObject("payload"));
            }
            packet.put("body", body);

            String jsonStr = packet.toString();

            // 1. TCP 전송 (로봇에게)
            PrintWriter writer = clients.get(targetId);
            if (writer != null) {
                writer.println(jsonStr);
                log(">> [Scenario -> " + targetId + "] " + desc);
            } else {
                log(">> [Scenario Error] 타겟 미접속: " + targetId);
            }

            // 2. [NEW] 웹소켓 브로드캐스트 (프론트엔드에게)
            if (wsServer != null) {
                wsServer.broadcast(jsonStr);
            }
        }
    }

    // --- ClientHandler ---
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
                // 접속 끊김
            } finally {
                if (clientID != null) {
                    clients.remove(clientID);
                    log(">> [TCP 종료] " + clientID);
                }
            }
        }

        private void processPacket(String jsonStr) {
            try {
                // 1. [NEW] 들어온 패킷을 그대로 웹소켓으로 중계 (Broadcast)
                if (wsServer != null) {
                    wsServer.broadcast(jsonStr);
                }

                // 2. 기존 로직 처리
                JSONObject root = new JSONObject(jsonStr);
                JSONObject header = root.getJSONObject("header");
                String sender = header.getString("sender_id");

                if (clientID == null) {
                    clientID = sender;
                    clients.put(clientID, out);
                    log(">> [TCP 접속] " + clientID + " 연결됨.");
                }

                log("<< [" + sender + "] " + header.getString("log_text"));

            } catch (Exception e) {
                System.out.println("Invalid Packet: " + e.getMessage());
            }
        }
    }
}