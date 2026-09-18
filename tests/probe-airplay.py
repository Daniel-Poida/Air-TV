import socket,sys
kind=sys.argv[1]
request=("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n" if kind=="options" else "POST /pair-pin-start RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n")
with socket.create_connection((sys.argv[2] if len(sys.argv)>2 else "192.168.0.31",7000),timeout=5) as sock:
 sock.sendall(request.encode("ascii"))
 response=sock.recv(4096).decode("ascii",errors="replace")
 print(response.splitlines()[0])
 assert "200" in response.splitlines()[0],response
