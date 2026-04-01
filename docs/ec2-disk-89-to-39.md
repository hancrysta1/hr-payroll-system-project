# EC2 디스크 89%→39% — journald 로그 상한선 설정으로 반복 장애 해결

## 문제

운영 중인 Ubuntu EC2 인스턴스가 갑자기 접속 불가 상태가 되었다.

서버가 이렇게 전체적으로 꺼지는 경우는 드물다.
그리고 5일 전에도 이렇게 서버가 나가면서 StatusCheckFailed 문제가 발생했기 때문에 같은 문제임을 대충 유추할 수 있었다.
재부팅하면 정상으로 돌아오지만 며칠 후 동일 현상이 재발한 걸 보면 근본적인 문제 해결이 필요했다.

## 분석

### AWS EC2 대시보드 체크

인스턴스 연결성 검사 실패가 뜬다.

검사에 실패한 원인을 파악하기 위해 CloudWatch를 살펴보니 상태 체크 실패를 원인으로, 설정해 둔 경보 알람 상태에 도달한 것을 확인할 수 있었다.

### 장애를 감지하기 위한 Status Check

AWS EC2에는 장애를 감지하기 위한 두 가지 서로 다른 Health Check가 있다.

- System Status Check
- Instance Status Check

어디에서 문제가 발생했는지를 구분하기 위해 구분해놓은 것인데 AWS 공식문서에 따르면

"System status checks monitor the AWS hardware and network."

즉 System Status Check는 AWS의 내부 네트워크 문제와 같은 물리 인프라 레벨에 문제가 있음을 체크하는 것이고

"Instance status checks monitor the software and network configuration of your individual instance."

Instance Status Check는 메모리 상태, 파일 시스템, OS 설정 등 EC2 안에 설치된 운영체제의 문제를 체크하는 것이다.

참고: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/monitoring-system-instance-status-check.html

대시보드를 다시 보면 시스템 상태 검사는 통과했으나 인스턴스 상태 검사가 실패한 것을 확인할 수 있다.
고로 OS 내부에 문제가 있다고 판단하여 일단 임시로 재부팅 조치를 취하여 서버를 다시 살려놓은 후, 문제를 파악하기 시작했다.

### 디스크 사용률 체크

`df -h`

/dev/root는 루트 파일 시스템이다.
이 루트 파일 시스템이 89%나 차있는 것을 확인할 수 있었다.

루트 파일 시스템 종류는 다음과 같다.

- /etc (시스템 설정)
- /var (로그, 캐시, 상태 파일)
- /var/log (journald, nginx, kernel 로그 등)
- /var/lib (docker, containerd, mysql/metastore 등)
- /usr (바이너리, 라이브러리)
- OS 서비스 상태 파일
- systemd 동작 파일

OS가 동작하기 위해 필요한 거의 모든 파일들이 /dev/root 안에 있기 때문에 OS 동작에 필요한 여유 공간이 11% 남짓 남은 것이나 다름 없다.

### 왜 여유 공간이 필요할까

리눅스 환경 상, 루트 파일 시스템의 구성 요소 중 하나인 systemd 서비스는 특히 모든 OS이벤트 발생 시 journald에 기록을 남기기 때문이다.

그러나 디스크 여유 공간이 부족하면 모든 쓰기 작업 시 ENOSPC (No space left on device) 오류가 발생하며 실패할 수 있기에 (Linux write(2) 문서 참고) 해당 디렉토리는 항상 여유 공간을 남겨 두어야 하는 것이다.

참고: https://man7.org/linux/man-pages/man2/write.2.html
https://www.freedesktop.org/software/systemd/man/systemd-journald.service.html

### 메모리 체크

혹시 몰라 메모리를 확인하기 위해 `free -m` 명령어를 입력해보았을 때 역시나 가용 메모리는 5G이상으로 OOM Killer가 동작할 조건이 되지 않는데다가 흔적도 따로 없어 메모리 문제는 아닌 것으로 판단할 수 있었다.

### 문제 특정

디스크의 문제임을 확인했으니 구체적으로 루트 파일 시스템 중 어떤 것이 문제일지 특정해야했다.

현재 상황은 다음과 같다.

- 며칠에 한번 씩 간헐적으로 문제가 발생
- 재부팅 시 서버가 잘 돌아감

정기적으로 재발하는 장애는 다음과 같은 특징이 있다고 한다.

- 로그가 계속 쌓여서 디스크를 먹는 경우
- 메모리 누수
- inode 증가
- 백그라운드 서비스의 반복 실패
- cron 혹은 timer 기반 문제

정기 구독 기능으로 일정한 기간마다 cron을 돌리긴 했지만 빈도가 잦지 않기에 (한달 한번) 문제가 발생할 만한 직접적인 원인은 아닐거라고 생각했다.

재부팅하면 죽는데 며칠 뒤 같은 문제가 발생하니 누적되는 로그로 인한 문제의 가능성이 높아 보였다.

그래서 아래 명령어를 통해 로그 내역을 정렬하여 뽑아보았다.

`sudo du -sh /var/log/* | sort -h`

그 결과 journal 로그의 용량이 압도적으로 컸다.

### journal 로그란

journald 로그는 systemd 기반 Linux에서 모든 중요한 이벤트를 OS가 직접 기록하는 대표적인 누적형 로그다.

systemd journald 설정 문서에 따르면

"If no limit is set, the journal may use all available disk space."
"The journal is primarily for system events."
"Long-term storage is not the focus."

journald는 기본적으로 용량 제한이 없기 때문에 디스크 부족 문제를 직접 유발하는 주요 요인 중 하나로 꼽힌다.
관리자가 별도로 SystemMaxUse 등을 설정하지 않으면, 시간이 지나면서 디스크가 허용하는 만큼 로그를 계속 늘려간다.

또한 system-level 이벤트 저장 목적이지 장기 보관 목적이 아님을 알 수 있다.
고로 오래 저장하는 용도가 아니다.

### 특정한 서비스의 폭주로 인한 것은 아닐까

`sudo journalctl --since "1 day ago" | awk '{print $5}' | sort | uniq -c | sort -nr | head`

서비스 목록과 용량은 정상적인 범주로 보인다.
즉 특정한 서비스의 문제로 로그가 쌓이는 것이 아니라, 용량의 제한이 없어 무제한으로 쌓이는 것이 문제.

Ubuntu Server Guide에 따르면 Ubuntu는 journald default 크기가 계속 증가할 수 있기 때문에 journald는 SystemMaxUse 미설정 시 디스크를 계속 사용한다고 나와있다.

또한

"Reduces the journal size until it is below the specified limit."

로 limit을 설정해두길 권고한다.

출처: https://www.freedesktop.org/software/systemd/man/journalctl.html

### 적절한 크기가 있을까

우분투에는 특정한 수치가 있는 건 아니지만 RedHat 공식문서에서는

```
SystemMaxUse=200M
SystemKeepFree=50M
SystemMaxFileSize=10M
```

와 같이 보관 정책을 직접 설정할 것을 권장한다.

## 해결

### 사이즈 설정

위와 같이 주석을 풀고 사이즈를 지정해두었다.

### 로그를 임의로 비워내도 괜찮을까

운영 시스템의 로그를 이렇게 임의로 비워내도 되는가하는 두려움이 들었다.
크기를 지정하면 로그가 날아갈 수 밖에 없는 구조기 때문.
그러나 journald는 시스템 이벤트 로그일 뿐, OS 부팅이나 서비스 운영에 필수 요소가 아니기 때문에 삭제해도 무방하다.

"Journal files are logs of system events. They are not required for the system to boot or operate."

출처: https://www.freedesktop.org/software/systemd/man/systemd-journald.service.html

또한 운영을 위해 일정 기간 쌓아두어야하는 애플리케이션 로그는 다음과 같이 journald와 별도 위치에 저장되기 때문에 삭제해도 운영에 무방하다.

- nginx → /var/log/nginx
- Spring Boot → 파일 로그 지정 경로
- Prometheus TSDB → /var/lib/prometheus

## 결과

쓰지 않는 컨테이너도 추가로 정리해주어 11% → 61%의 여유 공간을 확보했다.
당분간은 서버가 나가는 일은 없을 것 같다.

## 참고한 문서

- https://www.freedesktop.org/software/systemd/man/journald.conf.html
- https://www.freedesktop.org/software/systemd/man/systemd-journald.service.html
- https://www.freedesktop.org/software/systemd/man/journalctl.html
- https://man7.org/linux/man-pages/man2/write.2.html
- https://www.kernel.org/doc/gorman/html/understand/understand016.html
- https://snapcraft.io/docs/snapd
