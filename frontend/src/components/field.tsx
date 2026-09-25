/**
 * 라벨이 붙은 입력칸.
 *
 * **라벨을 컴포넌트가 든다.** 화면마다 손으로 붙이면 하나를 빠뜨렸을 때 그 칸만 낭독기에서
 * 이름 없는 입력칸이 된다(KWCAG 2.2). eslint 의 jsx-a11y 가 빠진 것을 잡지만, 안 빠뜨리는 쪽이 낫다.
 *
 * 부르는 곳은 로그인·가입·결제·탈퇴 폼이다. 칸 하나의 모양만 들고 판정은 폼이 한다.
 */
export function Field({
  name,
  label,
  type = "text",
  autoComplete,
  required = true,
}: {
  name: string;
  label: string;
  type?: string;
  autoComplete?: string;
  required?: boolean;
}) {
  return (
    <p>
      <label htmlFor={name}>{label}</label>
      <input
        id={name}
        name={name}
        type={type}
        autoComplete={autoComplete}
        required={required}
      />
    </p>
  );
}
