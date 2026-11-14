Copied as printed in MAL's authorization reference, https://myanimelist.net/apiconfig/references/authorization,
read on 2026-11-13: `token.json` under "Response to token requests", `expired_access_token.json` under "Refreshing
an access token", where it is the body of `HTTP/1.1 401 Unauthorized` with the header
`WWW-Authenticate: Bearer error="invalid_token",error_description="The access token expired"`.
