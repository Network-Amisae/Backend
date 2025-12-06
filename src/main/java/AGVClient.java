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

    // [수정] ID를 상수가 아닌 변수로 변경
    private String myId;

    // 생성자에서 ID를 받음
    public AGVClient(String id) {
        this.myId = id;
    }

    public static void main(String[] args) {
        // 실행 시 인자가 있으면 그걸 ID로 쓰고, 없으면 기본값 AGV_01
        String id = (args.length > 0) ? args[0] : "AGV_01";
        new AGVClient(id).start();
    }

    public void start() {
        try {
            Socket socket = new Socket(SERVER_IP, PORT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            System.out.println(">> [" + myId + "] 서버 연결 성공");

            // 1. 접속 시 STATUS 보고 (Active) - myId 변수 사용
            String loginPacket = JsonPacketBuilder.createStatusPacket(myId, "ACTIVE", false);
            out.println(loginPacket);

            // 2. 수신 대기 루프
            String line;
            while ((line = in.readLine()) != null) {
                handleServerMessage(line, out); // out을 넘겨줘서 응답 가능하게
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleServerMessage(String jsonStr, PrintWriter out) { // out 추가됨
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONObject header = root.getJSONObject("header");
            String type = header.getString("type");
            String receiver = header.getString("receiver_id");

            // [중요] 내 ID(myId)와 맞는지 확인
            if (!receiver.equals(myId) && !receiver.equals("ALL")) return;

            System.out.println("<< [" + myId + " 수신] " + header.getString("log_text"));

            if (type.equals("COMMAND")) {
                JSONObject body = root.getJSONObject("body");
                String command = body.getString("command");

                if ("MOVE_PATH".equals(command)) {
                    // 스레드로 분리하여 이동 시뮬레이션 (out 객체 전달)
                    new Thread(() -> simulateMovement(body, out)).start();
                }
            }

        } catch (Exception e) {
            System.out.println(">> Error: " + e.getMessage());
        }
    }

    private void simulateMovement(JSONObject body, PrintWriter out) {
        try {
            JSONObject payload = body.getJSONObject("payload");
            String taskId = body.getString("task_id");
            String dest = payload.getString("final_dest");
            JSONArray waypoints = payload.getJSONArray("waypoints");

            System.out.println(">> [" + myId + "] 이동 시작 -> " + dest);

            // 받은 경로대로 이동
            for (int i = 0; i < waypoints.length(); i++) {
                String qr = waypoints.getString(i);
                Thread.sleep(2000); // 2초 이동

                // 위치 보고 패킷 전송
                String packet = JsonPacketBuilder.createLocationPacket(myId, qr, dest, i + 1);
                out.println(packet);
                System.out.println(">> [" + myId + "] QR 통과: " + qr);
            }

            Thread.sleep(1000);
            // 도착 ACK 전송
            String ackPacket = JsonPacketBuilder.createAckPacket(myId, taskId, dest + " 도착 완료");
            out.println(ackPacket);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}