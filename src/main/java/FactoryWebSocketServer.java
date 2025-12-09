import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import java.net.InetSocketAddress;

public class FactoryWebSocketServer extends WebSocketServer {

    public FactoryWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println(">> [WS] 새로운 웹 클라이언트 접속: " + conn.getRemoteSocketAddress());
        // 접속하자마자 환영 메시지 하나 보내줌 (테스트용)
        conn.send("{\"type\":\"SYSTEM\", \"message\":\"Connected to Dashboard Server\"}");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println(">> [WS] 웹 클라이언트 접속 해제: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // 웹에서 보낸 메시지는 보통 무시하거나 로그만 찍음 (우리는 단방향 방송이 주 목적이므로)
        // System.out.println("From Web: " + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println(">> [WS] 웹소켓 방송 서버 시작됨 (Port: " + getPort() + ")");
    }
}
