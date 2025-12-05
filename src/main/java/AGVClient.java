import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class AGVClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 9001;
    private static final String MY_ID = "AGV_01"; // 내 ID

    private Socket socket;
    private PrintWriter out;
    private boolean isRunning = true;

    public static void main(String[] args) {
        new AGVClient().start();
    }

    public void start() {
        try {
            socket = new Socket(SERVER_IP, PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            System.out.println(">> [AGV] 서버 연결 성공 (" + MY_ID + ")");

            // 1. 접속 시 STATUS 보고 (Active)
            String loginPacket = JsonPacketBuilder.createStatusPacket(MY_ID, "ACTIVE", false);
            out.println(loginPacket);

            // 2. 수신 대기 루프
            String line;
            while (isRunning && (line = in.readLine()) != null) {
                handleServerMessage(line);
            }

        } catch (IOException e) {
            e.printStackTrace();
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
            System.out.println(">> JSON 파싱 에러: " + e.getMessage());
        }
    }

    // 이동 시뮬레이션 (Spec 2.3A: QR 기반 위치 보고)
    private void simulateMovement(JSONObject body) {
        try {
            JSONObject payload = body.getJSONObject("payload");
            String taskId = body.getString("task_id");
            String dest = payload.getString("target_cell"); // 혹은 final_dest

            // 서버가 경로(QR 리스트)를 주지 않았다면 가상의 경로 생성
            // 실제 구현에선 payload.getJSONArray("waypoints")를 사용
            String[] path = {"QR_ROAD_01", "QR_ROAD_02", "QR_ROAD_03", "QR_CELL_IN_" + dest};

            System.out.println(">> [동작] 이동 시작 -> 목적지: " + dest);

            for (int i = 0; i < path.length; i++) {
                Thread.sleep(2000); // 2초간 이동하는 척

                // Spec 2.3A: 현재 스캔한 QR 보고
                String packet = JsonPacketBuilder.createLocationPacket(MY_ID, path[i], dest, i + 1);
                out.println(packet);
                System.out.println(">> [전송] 위치 보고: " + path[i]);
            }

            // 도착 후 처리
            Thread.sleep(1000);

            // Spec 2.4: 도착 및 작업 완료 알림 (ACK)
            String ackPacket = JsonPacketBuilder.createAckPacket(MY_ID, taskId, dest + " 도착 및 하역 완료");
            out.println(ackPacket);
            System.out.println(">> [전송] 작업 완료 ACK");

            // 상태 업데이트 (Inactive)
            out.println(JsonPacketBuilder.createStatusPacket(MY_ID, "INACTIVE", false));

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}