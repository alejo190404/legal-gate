/** A page of a cursor-paged list; pass nextCursor back as ?cursor= for the page after it. */
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}
