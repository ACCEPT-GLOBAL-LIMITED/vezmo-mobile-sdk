/**
 * Expo config plugin for @vezmo/react-native.
 *
 *   plugins: [["@vezmo/react-native", { merchantIdentifier: "merchant.com.acme" }]]
 *
 * - iOS: adds Apple Pay (the In-App Payments entitlement with your Apple Merchant ID) and the camera text for
 *   scanning a card. Without a merchant identifier, Apple Pay is simply not offered.
 * - Android needs nothing: Google Pay's flag ships in the package's own manifest.
 */
function loadConfigPlugins() {
  try {
    return require('expo/config-plugins');
  } catch {
    // The package outside the app's node_modules (a monorepo, a local link): use the app's Expo.
    return require(require.resolve('expo/config-plugins', { paths: [process.cwd()] }));
  }
}
const { withEntitlementsPlist, withInfoPlist } = loadConfigPlugins();

const DEFAULT_CAMERA_TEXT = 'Scan your card to fill in its details.';

function withVezmo(config, props = {}) {
  const merchantIds = [].concat(props.merchantIdentifier || []).filter(Boolean);
  if (merchantIds.length) {
    config = withEntitlementsPlist(config, (c) => {
      const key = 'com.apple.developer.in-app-payments';
      c.modResults[key] = Array.from(new Set([...(c.modResults[key] || []), ...merchantIds]));
      return c;
    });
  }
  config = withInfoPlist(config, (c) => {
    if (!c.modResults.NSCameraUsageDescription) {
      c.modResults.NSCameraUsageDescription = props.cameraPermission || DEFAULT_CAMERA_TEXT;
    }
    return c;
  });
  return config;
}

module.exports = withVezmo;
