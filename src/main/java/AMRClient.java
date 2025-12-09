import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
// [추가] 시간 포맷팅을 위한 클래스 임포트
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class AMRClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 8888;

    // [추가] 시간 포맷터 정의 (시:분:초)
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private String myId;
    private Socket socket;
    private PrintWriter out;
    private boolean isRunning = true;

    public AMRClient(String id) {
        this.myId = id;
    }

    // [추가] 로그 출력 헬퍼 (일반)
    private void log(String msg) {
        String time = LocalTime.now().format(TIME_FMT);
        System.out.println("[" + time + "] " + msg);
    }

    // [추가] 로그 출력 헬퍼 (에러)
    private void logError(String msg) {
        String time = LocalTime.now().format(TIME_FMT);
        System.err.println("[" + time + "] " + msg);
    }

    public static void main(String[] args) {
        String clientId = (args.length > 0) ? args[0] : "AMR_01";

        // 메인 시작 로그에도 시간 적용
        String time = LocalTime.now().format(TIME_FMT);
        System.out.println("[" + time + "] >> 클라이언트 시작 모드: " + clientId);

        new AMRClient(clientId).start();
    }

    public void start() {
        try {
            socket = new Socket(SERVER_IP, PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // System.out -> log() 로 변경
            log(">> [AMR] 서버 연결 성공 (" + myId + ")");

            // 접속 시 STATUS 보고 (Active)
            String loginPacket = JsonPacketBuilder.createStatusPacket(myId, "AMR", "ACTIVE", false);
            out.println(loginPacket);

            String line;
            while (isRunning && (line = in.readLine()) != null) {
                handleServerMessage(line);
            }

        } catch (IOException e) {
            // System.err -> logError() 로 변경
            logError(">> [" + myId + " ERROR] 연결 실패: " + e.getMessage());
        } finally {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) { /* ignore */ }
        }
    }

    private void handleServerMessage(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONObject header = root.getJSONObject("header");
            String type = header.getString("type");
            String receiver = header.getString("receiver_id");

            if (!receiver.equals(myId) && !receiver.equals("ALL")) return;

            // 로그 출력 변경
            log("<< 수신: " + header.getString("log_text"));

            if (type.equals("COMMAND")) {
                JSONObject body = root.getJSONObject("body");
                String command = body.getString("command");

                if ("DELIVER_PART".equals(command) || "MOVE_PATH".equals(command) || "MOVE_CMD".equals(command)) {
                    new Thread(() -> simulateMovement(body)).start();
                }
            }

        } catch (Exception e) {
            logError(">> [" + myId + " ERROR] JSON 파싱 에러: " + e.getMessage());
        }
    }

    private void simulateMovement(JSONObject body) {
        try {
            JSONObject payload = body.getJSONObject("payload");
            String taskId = body.getString("task_id");

            // 목적지 키값 처리 (AGV/AMR 시나리오 호환성)
            String dest;
            if (payload.has("final_dest")) {
                dest = payload.getString("final_dest");
            } else {
                dest = payload.optString("target_cell", "BASE_STATION");
            }

            log(">> [동작] " + myId + " 이동 시작 -> " + dest);

            // 이동 시뮬레이션 (5초)
            Thread.sleep(5000);

            // ACK 전송
            String ackCommand = "ARRIVED_AT_" + dest.toUpperCase();
            String ackPacket = JsonPacketBuilder.createAckPacket(myId, "AMR", taskId, ackCommand);
            out.println(ackPacket);

            log(">> [전송] 작업 완료 ACK: " + ackCommand);

            // 상태 보고 (INACTIVE)
            out.println(JsonPacketBuilder.createStatusPacket(myId, "AMR", "INACTIVE", false));
            log(">> [전송] 상태 보고: INACTIVE");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}