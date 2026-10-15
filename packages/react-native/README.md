# @vezmo/react-native

Vezmo checkout for React Native and Expo apps: a native payment sheet for cards, Apple Pay, Google Pay, pay-later
methods, Cash App, UPI and Pix, in the buyer's own currency, with 3D Secure.

**Status: in development, not yet published.**

- New Architecture only (React Native 0.79+). Expo apps need a development build (not Expo Go).
- Your server creates an API checkout session with your Vezmo API key and passes its `clientToken` to the app.
  No API key goes into the app.
