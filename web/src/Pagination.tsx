import React from 'react';

interface PaginationProps {
  currentPage: number;
  totalItems: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  className?: string;
}

export default function Pagination({
  currentPage,
  totalItems,
  pageSize,
  onPageChange,
  className = '',
}: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));

  if (totalPages <= 1) {
    return null;
  }

  // Generate page numbers with ellipsis
  const getPages = () => {
    const pages: (number | string)[] = [];
    if (totalPages <= 7) {
      for (let i = 1; i <= totalPages; i++) pages.push(i);
    } else {
      pages.push(1);
      if (currentPage > 3) pages.push('...');
      const start = Math.max(2, currentPage - 1);
      const end = Math.min(totalPages - 1, currentPage + 1);
      for (let i = start; i <= end; i++) pages.push(i);
      if (currentPage < totalPages - 2) pages.push('...');
      pages.push(totalPages);
    }
    return pages;
  };

  const startItem = (currentPage - 1) * pageSize + 1;
  const endItem = Math.min(totalItems, currentPage * pageSize);

  return (
    <div className={`gc-pagination ${className}`}>
      <div className="gc-pagination-info">
        显示 <span>{startItem}</span>–<span>{endItem}</span> / 共 <span>{totalItems}</span> 项
      </div>
      <div className="gc-pagination-controls">
        <button
          type="button"
          className="gc-pagination-btn"
          disabled={currentPage <= 1}
          onClick={() => onPageChange(currentPage - 1)}
          aria-label="上一页"
        >
          ‹ 上一页
        </button>
        <div className="gc-pagination-pages">
          {getPages().map((p, idx) =>
            typeof p === 'number' ? (
              <button
                type="button"
                key={p}
                className={`gc-pagination-page ${currentPage === p ? 'active' : ''}`}
                onClick={() => onPageChange(p)}
                aria-current={currentPage === p ? 'page' : undefined}
              >
                {p}
              </button>
            ) : (
              <span key={`ellipsis-${idx}`} className="gc-pagination-ellipsis">
                …
              </span>
            )
          )}
        </div>
        <button
          type="button"
          className="gc-pagination-btn"
          disabled={currentPage >= totalPages}
          onClick={() => onPageChange(currentPage + 1)}
          aria-label="下一页"
        >
          下一页 ›
        </button>
      </div>
    </div>
  );
}
