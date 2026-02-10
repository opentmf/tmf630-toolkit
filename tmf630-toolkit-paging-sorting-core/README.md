# tmf630-toolkit-paging-sorting-core

TMF630 pagination, sorting, range headers, and field selection. No Spring Boot dependency.

## Usage

- `offset`, `limit` for TMF-style pagination
- `sort=-field,+field` for signed sort
- `Tmf630Util.tmfPage(Page)` for range headers and partial content
- `FieldSelectionUtil.fieldsToMap(obj, "id,name")` for field selection

Non-Boot users: create `Tmf630PagingSettings` and wire `TmfPageableHandlerMethodArgumentResolver` manually.
