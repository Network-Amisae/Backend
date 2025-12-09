import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshakeBuilder;

import java.net.InetSocketAddress;

public class FactoryWebSocketServer extends WebSocketServer {

    // 허용할 리액트 주소
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    public FactoryWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    // [핵심 로직] 접속 요청(Handshake) 단계에서 출처(Origin) 검사
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
        // 클라이언트가 보낸 헤더에서 'Origin' 값을 꺼냄
        String origin = request.getFieldValue("Origin");

        // 1. Origin이 비어있는 경우 (Java Client, Postman 등) -> 통과
        // (우리가 만든 AGV/AMR 서버 내부 통신용)
        if (origin == null || origin.isEmpty()) {
            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        }

        // 2. Origin이 있는 경우 (웹 브라우저) -> 주소 검사
        if (!origin.equals(ALLOWED_ORIGIN)) {
            System.err.println(">> [WS 차단] 허용되지 않은 출처 접근 시도: " + origin);
            // 3000번이 아니면 즉시 연결 거부 (예외 발생)
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Not Allowed Origin");
        }

        // 3. 검사 통과
        // System.out.println(">> [WS 허용] 인증된 출처: " + origin);
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // 접속 로그를 조금 더 상세하게 (React인지 Java인지 구분)
        String origin = handshake.getFieldValue("Origin");
        String clientType = (origin != null && !origin.isEmpty()) ? "React Frontend" : "Java Backend Client";

        System.out.println(">> [WS] 접속 성공 (" + clientType + "): " + conn.getRemoteSocketAddress());

        // 환영 메시지 전송
        conn.send("{\"type\":\"SYSTEM\", \"message\":\"Connected to Factory Dashboard\"}");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // System.out.println(">> [WS] 접속 해제: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // 웹에서 서버로 메시지를 보낼 일이 있다면 여기서 처리
        // System.out.println("From Web: " + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // 에러 로그 (너무 길면 ex.getMessage()만 출력해도 됨)
        // ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println(">> [WS] 웹소켓 방송 서버 가동 (Port: " + getPort() + ")");
    }
}