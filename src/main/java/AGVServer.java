import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

// WebSocket 라이브러리 (Java-WebSocket)
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

public class AGVServer {

    private static final int TCP_PORT = 9001; // AGV/AMR 통신용
    private static final int WS_PORT = 9002;  // 웹 모니터링용
    private static final String SERVER_ID = "AGV_SERVER"; // 서버 ID (AMR_SERVER로 변경 가능)

    // 시간 포맷터
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 클라이언트 소켓 저장소 (ID -> PrintWriter)
    private static Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    // 웹소켓 서버 인스턴스
    private static SimpleWebSocketServer wsServer;

    public static void main(String[] args) {
        printLog("SYSTEM", "시스템 부팅 중...");

        // 1. 웹소켓 서버 시작
        wsServer = new SimpleWebSocketServer(WS_PORT);
        wsServer.start();
        printLog("SYSTEM", "웹소켓 방송 서버 시작 (Port: " + WS_PORT + ")");

        // 2. 시나리오 실행 스레드 시작 (파일이름 확인 필수)
        new Thread(new ScenarioRunner("agv_scenario.json")).start();

        // 3. TCP 소켓 서버 시작
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            printLog("SYSTEM", "TCP 연결 대기 중 (Port: " + TCP_PORT + ")");
            while (true) {
                new ClientHandler(serverSocket.accept()).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // [예쁜 로그 출력 헬퍼]
    // 포맷: [시간] [TYPE   ] Sender -> Receiver : 메시지 내용
    private static void printPrettyLog(String type, String sender, String receiver, String text) {
        String time = LocalTime.now().format(TIME_FMT);
        String flow = String.format("%s -> %s", sender, receiver);

        // 콘솔 출력 포맷 (글자수 정렬)
        System.out.printf("[%s] [%-8s] %-25s : %s%n", time, type, flow, text);
    }

    // 시스템 로그용
    private static void printLog(String tag, String msg) {
        System.out.printf("[%s] [%-8s] %s%n", LocalTime.now().format(TIME_FMT), tag, msg);
    }

    // --- 웹소켓 서버 클래스 ---
    static class SimpleWebSocketServer extends WebSocketServer {
        public SimpleWebSocketServer(int port) {
            super(new InetSocketAddress(port));
        }
        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // 접속 시 조용히 처리
        }
        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {}
        @Override
        public void onMessage(WebSocket conn, String message) {}
        @Override
        public void onError(WebSocket conn, Exception ex) { ex.printStackTrace(); }
        @Override
        public void onStart() {}
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
                printLog("SCENARIO", "10초 후 시나리오를 시작합니다.");
                Thread.sleep(10000);

                File file = new File(filePath);
                if (!file.exists()) {
                    printLog("ERROR", "파일 없음: " + filePath);
                    return;
                }

                String content = new String(Files.readAllBytes(Paths.get(filePath)));
                JSONArray scenarios = new JSONArray(content);
                printLog("SCENARIO", "로드 완료 (" + scenarios.length() + " steps)");

                long startTime = System.currentTimeMillis();

                for (int i = 0; i < scenarios.length(); i++) {
                    JSONObject step = scenarios.getJSONObject(i);
                    long offset = step.getLong("time_offset_ms");

                    // 타이밍 맞추기
                    long currentTime = System.currentTimeMillis() - startTime;
                    long waitTime = offset - currentTime;
                    if (waitTime > 0) Thread.sleep(waitTime);

                    processScenarioStep(step);
                }
                printLog("SCENARIO", "모든 시나리오 종료.");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void processScenarioStep(JSONObject step) {
            // 1. JSON에서 정보 추출 (수정된 키 반영)
            String sender = step.getString("sender_id");
            String receiver = step.getString("receiver_id");
            String type = step.getString("message_type");
            String command = step.getString("command");
            String desc = step.getString("description"); // 채팅형 메시지
            String taskId = step.optString("task_id", "TASK_000");

            // 2. 전송할 패킷 생성 (프로토콜 표준화)
            JSONObject packet = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("type", type);
            header.put("sender_id", sender);
            header.put("receiver_id", receiver);
            header.put("timestamp", LocalTime.now().format(TIME_FMT));
            header.put("log_text", desc); // UI 표시용 텍스트
            packet.put("header", header);

            JSONObject body = new JSONObject();
            body.put("task_id", taskId);
            body.put("command", command);
            if (step.has("payload")) {
                body.put("payload", step.getJSONObject("payload"));
            }
            packet.put("body", body);

            String jsonStr = packet.toString();

            // 3. 로그 출력 (예쁘게)
            printPrettyLog(type, sender, receiver, desc);

            // 4. 웹소켓 브로드캐스트 (웹 UI 갱신용) -> 무조건 보냄 (시뮬레이션 효과)
            if (wsServer != null) {
                wsServer.broadcast(jsonStr);
            }

            // 5. TCP 전송 로직 (중요!)
            // 시나리오 상 '보내는 사람'이 'SERVER'인 경우에만 실제로 TCP 패킷을 쏩니다.
            // (Robot이 보내는 메시지는 시나리오상 '기대값'이거나 '시뮬레이션'이므로 서버가 쏘지 않음)
            if (sender.contains("SERVER")) {
                PrintWriter writer = clients.get(receiver);
                if (writer != null) {
                    writer.println(jsonStr);
                } else {
                    // 실제 로봇이 안 붙어있어도 시나리오는 돌아가게 둠 (로그만 남김)
                    // System.out.println("  -> (전송 실패: " + receiver + " 미접속)");
                }
            }
        }
    }

    // --- TCP 클라이언트 핸들러 ---
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
                    handleIncomingPacket(line);
                }
            } catch (IOException e) {
                // 접속 끊김
            } finally {
                if (clientID != null) {
                    clients.remove(clientID);
                    printLog("TCP", clientID + " 접속 해제");
                }
            }
        }

        private void handleIncomingPacket(String jsonStr) {
            try {
                JSONObject root = new JSONObject(jsonStr);
                JSONObject header = root.getJSONObject("header");

                String sender = header.getString("sender_id");
                String receiver = header.getString("receiver_id");
                String type = header.getString("type");
                String desc = header.optString("log_text", "");

                // ID 등록 (최초 1회)
                if (clientID == null) {
                    clientID = sender;
                    clients.put(clientID, out);
                    printLog("TCP", clientID + " 연결됨 (" + socket.getInetAddress() + ")");
                }

                printPrettyLog(type, sender, receiver, desc);

                // 2. 웹소켓 중계 (웹 모니터링용)
                if (wsServer != null) {
                    wsServer.broadcast(jsonStr);
                }

            } catch (Exception e) {
                System.out.println("Invalid Packet: " + e.getMessage());
            }
        }
    }
}