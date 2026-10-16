# Vezmo Flutter example

Northwind Audio, a small shop with one product and a club subscription, that pays against Vezmo's sandbox with no
setup: **New sandbox session** makes a test session on Vezmo's dev sandbox, **Buy now** opens the Vezmo sheet.

```sh
flutter run   # on an Android phone or emulator
```

Pay with the test card `4242 4242 4242 4242`, any future expiry and any CVC. `VEZMO_API_BASE` (a `--dart-define`)
points the app at another Vezmo API; it defaults to the dev sandbox.
