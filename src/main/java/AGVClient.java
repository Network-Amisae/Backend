import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
// [추가] 시간 포맷팅을 위한 클래스 임포트
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class AGVClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 9001;

    // [추가] 시간 포맷터 정의 (시:분:초)
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private String myId;

    public AGVClient(String id) {
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
        String id = (args.length > 0) ? args[0] : "AGV_01";

        // 메인 시작 로그도 시간 적용
        String time = LocalTime.now().format(TIME_FMT);
        System.out.println("[" + time + "] >> AGV 클라이언트 시작 (ID: " + id + ")");

        new AGVClient(id).start();
    }

    public void start() {
        try {
            Socket socket = new Socket(SERVER_IP, PORT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // System.out -> log() 변경
            log(">> [" + myId + "] 서버 연결 성공");

            // 1. 접속 시 STATUS 보고 (Active)
            String loginPacket = JsonPacketBuilder.createStatusPacket(myId, "ACTIVE", false);
            out.println(loginPacket);

            // 2. 수신 대기 루프
            String line;
            while ((line = in.readLine()) != null) {
                handleServerMessage(line, out);
            }

        } catch (IOException e) {
            logError(">> Connection Error: " + e.getMessage());
        }
    }

    private void handleServerMessage(String jsonStr, PrintWriter out) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONObject header = root.getJSONObject("header");
            String type = header.getString("type");
            String receiver = header.getString("receiver_id");

            // 내 ID 확인
            if (!receiver.equals(myId) && !receiver.equals("ALL")) return;

            // 로그 변경
            log("<< [" + myId + " 수신] " + header.getString("log_text"));

            if (type.equals("COMMAND")) {
                JSONObject body = root.getJSONObject("body");
                String command = body.getString("command");

                if ("MOVE_PATH".equals(command)) {
                    // 스레드로 분리하여 이동 시뮬레이션
                    new Thread(() -> simulateMovement(body, out)).start();
                }
            }

        } catch (Exception e) {
            logError(">> Packet Handling Error: " + e.getMessage());
        }
    }

    private void simulateMovement(JSONObject body, PrintWriter out) {
        try {
            JSONObject payload = body.getJSONObject("payload");
            String taskId = body.getString("task_id");
            String dest = payload.getString("final_dest");
            JSONArray waypoints = payload.getJSONArray("waypoints");

            log(">> [" + myId + "] 이동 시작 -> " + dest);

            // 받은 경로대로 이동
            for (int i = 0; i < waypoints.length(); i++) {
                String qr = waypoints.getString(i);
                Thread.sleep(2000); // 2초 이동

                // 위치 보고 패킷 전송
                String packet = JsonPacketBuilder.createLocationPacket(myId, qr, dest, i + 1);
                out.println(packet);

                log(">> [" + myId + "] QR 통과: " + qr);
            }

            Thread.sleep(1000);
            // 도착 ACK 전송
            String ackPacket = JsonPacketBuilder.createAckPacket(myId, taskId, dest + " 도착 완료");
            out.println(ackPacket);

            log(">> [" + myId + "] 도착 완료 ACK 전송");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}