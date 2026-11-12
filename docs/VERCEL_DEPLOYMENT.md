# Vercel 배포 가이드

FrameCritic 웹은 정적 사이트다. 서버 코드도, 환경 변수도, 데이터베이스도 없다. 사진은 사용자의
브라우저를 떠나지 않고, Vercel은 빌드된 HTML·CSS·JS 파일만 제공한다.

- 소스: `web/` (Vite + React + TypeScript + Tailwind CSS)
- 빌드 명령: `npm run build` (`tsc --noEmit` 후 `vite build`)
- 출력 디렉터리: `web/dist`
- 런타임: Node 18 이상 (Vercel 기본값 그대로 사용)

## 1. GitHub 저장소에서 임포트

1. <https://vercel.com/new> 을 연다.
2. **Import Git Repository** 에서 `plenuen-cpu/FrameCritic` 을 고른다.
   (목록에 없으면 **Adjust GitHub App Permissions** 로 저장소 접근을 허용한다.)
3. 프로젝트 설정에서 다음 세 가지만 확인한다.

| 항목 | 값 |
| --- | --- |
| Framework Preset | **Vite** |
| Root Directory | **`web`** |
| Build Command | `npm run build` (기본값) |
| Output Directory | `dist` (기본값) |

Root Directory를 `web`으로 두면 `web/vercel.json` 이 그대로 적용된다.

4. **Deploy** 를 누른다. 첫 빌드는 1분 안팎이고, 끝나면 `https://<프로젝트명>.vercel.app` 주소가 나온다.

## 2. 저장소 루트에서 임포트하는 경우

Root Directory를 비워 두고 저장소 루트를 그대로 쓸 때는 루트의 `vercel.json` 이 빌드를 대신
설정한다.

```json
{
  "installCommand": "cd web && npm install",
  "buildCommand": "cd web && npm run build",
  "outputDirectory": "web/dist"
}
```

이 경우 Framework Preset은 **Other** 로 두어도 된다.

## 3. 빌드할 때 내려받는 것

빌드(`npm run build`)는 먼저 `node scripts/prepare-assets.cjs`를 돌린다(`prebuild`). 이 단계에서

- NIMA 모델 두 개(`nima_aesthetic.tflite`, `nima_technical.tflite`, 각 6.4MB)를 Hugging Face에서
  받아 SHA-256을 `app/src/main/assets/models/manifest.json`과 대조하고, 동적 배치 축을 1로 고정한다.
- LiteRT.js WASM 런타임 파일을 `node_modules/@litertjs/core/wasm`에서 `public/litert/`로 복사한다.

두 산출물(`web/public/models`, `web/public/litert`)은 `.gitignore`에 있으므로 저장소에는 들어가지 않는다.
따라서 Vercel 빌드에는 **외부 네트워크(Hugging Face) 접근**이 필요하다. 빌드 로그에
`자산 준비 실패: ...` 가 보이면 그 원인이고, 이때는 배포가 실패하며 직전 성공 배포가 계속 서비스된다.

## 4. 배포 후 확인

- 첫 화면에 `FRAMECRITIC` 헤더와 사진을 떨어뜨리는 영역이 보이는지
- 사진을 넣고 **사진 분석하기** 를 눌렀을 때 점수와 열 개의 비평 카드가 나오는지
- **상세 측정 데이터 및 기술적 근거** 를 펼쳤을 때 `NIMA 미적 점수`, `NIMA 기술 점수`,
  `점수 출처: 브라우저 내 NIMA 추론` 과 모델 추론 시간이 보이는지 (모델을 못 받으면 `미적 추정 점수`,
  `캔버스 측정 기반 추정`으로 표기된다)
- 브라우저 개발자 도구의 Network 탭에서 이미지 업로드 요청이 **없는지** (사진은 전송되지 않는다).
  `/models/*.tflite` 와 `/litert/*.wasm` 은 내려받지만, 올라가는 요청은 없다.

## 5. 갱신

`rebuild/offline-v2` 브랜치에 푸시하면 Vercel이 자동으로 다시 빌드한다. 다른 브랜치와 풀
리퀘스트는 미리보기(preview) 배포로만 올라가고 프로덕션 주소는 바뀌지 않는다.

CLI로 직접 배포할 때는 다음과 같다.

```bash
npm i -g vercel
cd web
vercel            # 미리보기 배포
vercel --prod     # 프로덕션 배포
```

## 6. 도메인과 캐시

- 기본 도메인(`*.vercel.app`)으로 충분하다. 별도 도메인을 붙이려면 **Settings → Domains**.
- 정적 자산은 파일명에 해시가 붙어(`assets/index-<hash>.js`) 장기 캐시해도 안전하다.
  `index.html` 만 짧게 캐시된다.
- 서버리스 함수가 없으므로 요청 한도나 실행 시간 제한을 걱정할 필요가 없다. 무료(Hobby) 플랜의
  대역폭 안에서 정적 파일 제공만 일어난다.

## 7. 빌드가 실패할 때

| 증상 | 원인과 해결 |
| --- | --- |
| `tsc: not found` | Root Directory가 `web`이 아니어서 `web/package.json` 이 설치되지 않았다. Root Directory를 `web`으로 바꾸거나 루트 `vercel.json` 을 사용한다. |
| `Cannot find module '@/...'` | 별칭(alias)을 쓰지 않는다. 모든 import는 상대 경로다. |
| 빌드는 되는데 404 | Output Directory가 `dist`가 아니라 `web/dist`(루트 설정) 또는 그 반대다. 임포트 방식에 맞춰 하나만 쓴다. |
| Node 버전 경고 | Vercel 프로젝트 설정의 Node.js Version을 18.x 이상으로 둔다. |

## 8. 로컬에서 먼저 확인

```bash
cd web
npm install
npm run dev        # http://localhost:5173
npm run build      # 타입 검사 + 정적 빌드
npm run preview    # 빌드 결과를 로컬에서 확인
```

웹 앱은 기기 앱과 같은 TypeScript/Kotlin 비평 엔진 규칙을 쓴다. 미적·기술 점수는 브라우저에서 직접
돌린 NIMA 신경망의 출력(10구간 확률분포의 가중 평균)이고, 모델을 쓸 수 없을 때만 캔버스 측정값에서
계산한 추정으로 대체하며 화면과 문서에 그 사실을 함께 적는다. '주요 영역' 상자는 객체 검출기가 아니라
블록 대비·에지 밀도로 찾은 주의 영역이다.
