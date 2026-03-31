import { useRef } from 'react';

export default function DateInput(props) {
  const inputRef = useRef(null);
  const { onFocus, onClick, ...rest } = props;

  function openPicker() {
    const input = inputRef.current;
    if (!input || input.disabled || input.readOnly || typeof input.showPicker !== 'function') {
      return;
    }

    try {
      input.showPicker();
    } catch {
      // ignore: some browsers restrict showPicker calls
    }
  }

  function handleFocus(event) {
    onFocus?.(event);
    openPicker();
  }

  function handleClick(event) {
    onClick?.(event);
    openPicker();
  }

  return <input ref={inputRef} type="date" onFocus={handleFocus} onClick={handleClick} {...rest} />;
}
