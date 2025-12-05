import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class AMRClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 8888; // AMR DCC 서버 포트
    private static final String MY_ID = "AMR_01"; // 내 ID

    private Socket socket;
    private PrintWriter out;
    private boolean isRunning = true;

    public static void main(String[] args) {
        new AMRClient().start();
    }

    public void start() {
        try {
            socket = new Socket(SERVER_IP, PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            System.out.println(">> [AMR] 서버 연결 성공 (" + MY_ID + ")");

            // 1. [수정] 접속 시 STATUS 보고 (Active)
            // 인자 4개: (ID, 장비타입, 모드, 점유여부)
            // AMR은 점유여부(isOccupied)가 없으므로 false로 넘깁니다.
            String loginPacket = JsonPacketBuilder.createStatusPacket(MY_ID, "AMR", "ACTIVE", false);
            out.println(loginPacket);

            // 2. 수신 대기 루프
            String line;
            while (isRunning && (line = in.readLine()) != null) {
                handleServerMessage(line);
            }

        } catch (IOException e) {
            System.err.println(">> [AMR ERROR] 연결 실패: " + e.getMessage());
        } finally {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) { /* ignore */ }
        }
    }

    // 서버 메시지 처리 (라우팅 및 명령 수행)
    private void handleServerMessage(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONObject header = root.getJSONObject("header");
            String type = header.getString("type");
            String receiver = header.getString("receiver_id");

            // 나한테 온 메시지인지 확인 (Broadcast 포함)
            if (!receiver.equals(MY_ID) && !receiver.equals("ALL")) return;

            // 로그 출력 (채팅 로그 역할)
            System.out.println("<< 수신: " + header.getString("log_text"));

            // Spec 2.1: 물류 임무 할당 (COMMAND) 처리
            if (type.equals("COMMAND")) {
                JSONObject body = root.getJSONObject("body");
                String command = body.getString("command");

                if ("DELIVER_PART".equals(command) || "MOVE_CMD".equals(command)) {
                    // 이동 시뮬레이션 스레드 시작 (메인 스레드 차단 방지)
                    new Thread(() -> simulateMovement(body)).start();
                }
            }

        } catch (Exception e) {
            System.out.println(">> [AMR ERROR] JSON 파싱 에러: " + e.getMessage());
        }
    }

    // 이동 시뮬레이션 및 상태 보고 (Spec 2.3B 및 2.4)
    private void simulateMovement(JSONObject body) {
        try {
            JSONObject payload = body.getJSONObject("payload");
            String taskId = body.getString("task_id");
            String dest = payload.optString("target_cell", "BASE_STATION");

            System.out.println(">> [동작] 이동 시작 -> 목적지: " + dest);

            // 1. 이동 시뮬레이션
            Thread.sleep(5000); // 5초간 이동 시뮬레이션

            // 2. 도착 후 처리 및 보고

            // [수정] Spec 2.4: 도착 및 작업 완료 알림 (ACK)
            // 반드시 "AMR" 타입을 명시해야 DCC_SERVER로 전송됩니다.
            String ackCommand = "ARRIVED_AT_" + dest.toUpperCase();
            String ackPacket = JsonPacketBuilder.createAckPacket(MY_ID, "AMR", taskId, ackCommand);
            out.println(ackPacket);
            System.out.println(">> [전송] 작업 완료 ACK: " + ackCommand);

            // [수정] Spec 2.3B: 작업 완료 후 상태 업데이트 (Inactive)
            // 인자 4개: (ID, 장비타입, 모드, 점유여부)
            out.println(JsonPacketBuilder.createStatusPacket(MY_ID, "AMR", "INACTIVE", false));
            System.out.println(">> [전송] 상태 보고: INACTIVE");


        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}