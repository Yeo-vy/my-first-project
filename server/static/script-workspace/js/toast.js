/**
 * 화면 아래쪽 가운데에 잠깐 떴다 사라지는 안내 문구.
 * 스크린 리더가 읽을 수 있도록 role="status" 와 aria-live="polite" 를 답니다.
 */
export function createToast({ duration = 1800 } = {}) {
  const element = document.createElement('div');
  element.className = 'toast';
  element.setAttribute('role', 'status');
  element.setAttribute('aria-live', 'polite');

  let timer = null;

  return {
    element,
    show(message) {
      element.textContent = message;
      element.classList.add('is-on');
      clearTimeout(timer);
      timer = setTimeout(() => element.classList.remove('is-on'), duration);
    },
  };
}
