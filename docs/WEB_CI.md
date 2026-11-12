# 웹 앱 CI (선택)

웹 앱의 타입 검사와 빌드를 GitHub Actions에서도 돌리려면 아래 파일을
`.github/workflows/web.yml` 로 저장하고 푸시하면 된다.

지금 저장소에는 이 워크플로우가 커밋되어 있지 않다. 이 저장소에 연결된 GitHub App에
`workflows` 권한이 없어 워크플로우 파일을 푸시할 수 없기 때문이다. 저장소 설정에서 Pickle 앱의
워크플로우 권한을 허용하거나, 아래 내용을 직접 추가하면 된다. 그때까지 웹 빌드 검증은 로컬에서
`cd web && npm run build`, `node scripts/contract-check.cjs` 로 수행한다.

```yaml
name: FrameCritic Web

on:
  push:
    branches: [rebuild/offline-v2]
    paths:
      - 'web/**'
      - '.github/workflows/web.yml'
  pull_request:
    paths:
      - 'web/**'
  workflow_dispatch:

permissions:
  contents: read
concurrency:
  group: framecritic-web-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    defaults:
      run:
        working-directory: web
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: web/package-lock.json
      - name: Install
        run: npm ci --no-audit --no-fund
      - name: Type check
        run: npx tsc --noEmit
      - name: Build
        run: npm run build
      - name: Engine contract check (no digits or machine words in the critique)
        run: |
          npx tsc src/lib/domain.ts src/lib/critic-engine.ts src/lib/analyzer.ts \
            --outDir .verify --module commonjs --target es2020 --moduleResolution node --skipLibCheck
          echo '{"type":"commonjs"}' > .verify/package.json
          node scripts/contract-check.cjs
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: FrameCritic-Web
          path: |
            web/dist/
            web/.verify/
          if-no-files-found: warn
```
