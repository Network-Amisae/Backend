import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class AMRClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 8888;

    // [수정 1] final을 제거하고 인스턴스 변수로 변경 (고정값 삭제)
    private String myId;

    private Socket socket;
    private PrintWriter out;
    private boolean isRunning = true;

    // [수정 2] 생성자에서 ID를 설정하도록 변경
    public AMRClient(String id) {
        this.myId = id;
    }

    public static void main(String[] args) {
        // [수정 3] 실행 시 입력된 인자가 있으면 그걸 ID로 사용, 없으면 기본값 AMR_01
        String clientId = (args.length > 0) ? args[0] : "AMR_01";
        System.out.println(">> 클라이언트 시작 모드: " + clientId);

        new AMRClient(clientId).start();
    }

    public void start() {
        try {
            socket = new Socket(SERVER_IP, PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            System.out.println(">> [AMR] 서버 연결 성공 (" + myId + ")");

            // [수정 4] MY_ID 대신 myId 변수 사용
            // 접속 시 STATUS 보고 (Active)
            String loginPacket = JsonPacketBuilder.createStatusPacket(myId, "AMR", "ACTIVE", false);
            out.println(loginPacket);

            String line;
            while (isRunning && (line = in.readLine()) != null) {
                handleServerMessage(line);
            }

        } catch (IOException e) {
            System.err.println(">> [" + myId + " ERROR] 연결 실패: " + e.getMessage());
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

            // [수정 5] 수신자가 '나(myId)'인 경우만 처리
            if (!receiver.equals(myId) && !receiver.equals("ALL")) return;

            System.out.println("<< 수신: " + header.getString("log_text"));

            if (type.equals("COMMAND")) {
                JSONObject body = root.getJSONObject("body");
                String command = body.getString("command");

                if ("DELIVER_PART".equals(command) || "MOVE_CMD".equals(command)) {
                    new Thread(() -> simulateMovement(body)).start();
                }
            }

        } catch (Exception e) {
            System.out.println(">> [" + myId + " ERROR] JSON 파싱 에러: " + e.getMessage());
        }
    }

    private void simulateMovement(JSONObject body) {
        try {
            JSONObject payload = body.getJSONObject("payload");
            String taskId = body.getString("task_id");
            String dest = payload.optString("target_cell", "BASE_STATION");

            System.out.println(">> [동작] " + myId + " 이동 시작 -> " + dest);

            Thread.sleep(5000);

            // [수정 6] ACK 및 상태 보고 시 myId 사용
            String ackCommand = "ARRIVED_AT_" + dest.toUpperCase();
            String ackPacket = JsonPacketBuilder.createAckPacket(myId, "AMR", taskId, ackCommand);
            out.println(ackPacket);
            System.out.println(">> [전송] 작업 완료 ACK: " + ackCommand);

            out.println(JsonPacketBuilder.createStatusPacket(myId, "AMR", "INACTIVE", false));
            System.out.println(">> [전송] 상태 보고: INACTIVE");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}