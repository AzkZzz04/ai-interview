"use client";

export function EvidenceRefs({ ids }: { ids?: string[] }) {
  const uniqueIds = [...new Set(ids?.filter(Boolean) ?? [])];
  if (!uniqueIds.length) {
    return null;
  }

  return (
    <div className="evidence-refs" aria-label="Retrieved evidence">
      <strong>Evidence</strong>
      <div>
        {uniqueIds.map((id) => (
          <span key={id}>{id}</span>
        ))}
      </div>
    </div>
  );
}
