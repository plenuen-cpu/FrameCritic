/**
 * LiteRT.js 실행기를 한 번만 준비해 NIMA와 피사체 탐지기가 함께 쓴다.
 * WebAssembly를 쓸 수 없으면 null을 돌려주고, 두 기능 모두 휴리스틱으로 넘어간다.
 */

type Core = typeof import('@litertjs/core')

let corePromise: Promise<Core | null> | null = null

/** 페이지 주소를 기준으로 정적 자산 주소를 만든다(하위 경로 배포에도 맞는다). */
export function assetUrl(path: string): string {
  return new URL(path, document.baseURI).href
}

export function loadRuntime(): Promise<Core | null> {
  if (!corePromise) {
    corePromise = import('@litertjs/core')
      .then(async (core) => {
        await core.loadLiteRt(assetUrl('litert/'))
        return core
      })
      .catch(() => null)
  }
  return corePromise
}
