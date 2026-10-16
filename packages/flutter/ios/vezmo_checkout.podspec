# The plugin's iOS side for apps that still use CocoaPods. Swift Package Manager (Flutter's default) reads
# vezmo_checkout/Package.swift instead.
Pod::Spec.new do |s|
  s.name             = 'vezmo_checkout'
  s.version          = '0.1.0'
  s.summary          = 'The Vezmo payment sheet for Flutter apps.'
  s.description      = 'The Vezmo payment sheet for Flutter apps: payments and subscriptions in a native sheet.'
  s.homepage         = 'https://vezmo.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Vezmo' => 'support@vezmo.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'vezmo_checkout/Sources/vezmo_checkout/**/*.swift'
  s.resource_bundles = { 'vezmo_checkout_privacy' => ['vezmo_checkout/Sources/vezmo_checkout/PrivacyInfo.xcprivacy'] }
  s.dependency 'Flutter'
  s.platform = :ios, '15.1'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
