"use client";

export function InsightList({
  title,
  items,
  tone
}: {
  title: string;
  items: string[];
  tone: "positive" | "warning";
}) {
  return (
    <div className={`insight-list ${tone}`}>
      <strong>{title}</strong>
      {items.map((item) => (
        <p key={item}>{item}</p>
      ))}
    </div>
  );
}
