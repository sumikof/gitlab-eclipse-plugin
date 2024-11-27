FROM ubuntu:20.04
RUN apt update -qy && apt install -qy openjdk-21-jdk libwebkit2gtk-4.0-37 x11vnc xvfb

RUN mkdir ~/.vnc
ENV DISPLAY host.docker.internal:0.0
EXPOSE 5900/tcp
ENV VNC_PASSWORD=
WORKDIR /gitlab-eclipse-plugin
ENTRYPOINT test -n "$VNC_PASSWORD" && x11vnc -storepasswd "$VNC_PASSWORD" ~/.vnc/passwd; x11vnc -forever -usepw -create
