import { useState } from "react";

type Props = { initial?: number; label: string };

export function Counter({ initial = 0, label }: Props) {
  const [count, setCount] = useState<number>(initial);
  return (
    <div className="counter">
      <span>{label}: {count}</span>
      <button onClick={() => setCount((c) => c + 1)} disabled={count >= 10}>
        Increment
      </button>
    </div>
  );
}
