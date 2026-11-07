/** UI views consume complete response records; OpenAPI keeps wire optionality.
 * Field names, nested shapes and enum values come from generated schemas.
 * Historical omissions/nulls and UI-only refinements are declared at each view. */
export type ResponseView<T> = T extends Array<infer Item> ? ResponseView<Item>[]
  : T extends object ? { [Key in keyof T]-?: ResponseView<T[Key]> }
  : T;
