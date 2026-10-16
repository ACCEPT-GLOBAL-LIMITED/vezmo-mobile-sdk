import 'dart:convert';
import 'dart:io';
import 'dart:math' show pi;

import 'package:flutter/material.dart';
import 'package:vezmo_checkout/vezmo_checkout.dart';

/// The Vezmo Flutter example: a small shop with one product and a Buy button that opens the Vezmo sheet. A real app
/// gets the session's client token from its own server; here it comes from the sandbox demo endpoint or is pasted in
/// under Sandbox.
void main() => runApp(const MaterialApp(debugShowCheckedModeBanner: false, home: Shop()));

/// Vezmo's dev sandbox (TEST mode only): its demo endpoints make sessions with no key in the app.
const apiBase = String.fromEnvironment('VEZMO_API_BASE', defaultValue: 'https://api.dev.vezmo.com/api/v1');

const ink = Color(0xFF0B0C14);
const grey = Color(0xFF5A5F70);
const line = Color(0xFFE1E3EC);
const dark = Color(0xFF2B2D3A);
const lilac = Color(0xFFE9E7F7);

class Shop extends StatefulWidget {
  const Shop({super.key});

  @override
  State<Shop> createState() => _ShopState();
}

class _ShopState extends State<Shop> {
  final vezmo = VezmoCheckout(
    configuration: const VezmoConfiguration(
      returnUrl: 'vezmoexample://vezmo',
      // The app's own Apple Merchant ID (Apple Developer → Identifiers → Merchant IDs): Apple Pay shows only with it.
      applePay: ApplePayOptions(merchantId: 'merchant.com.vezmo.example'),
      apiBase: apiBase,
    ),
  );
  final token = TextEditingController();
  String status = '';
  CheckoutResult? result;

  @override
  void initState() {
    super.initState();
    token.addListener(() => setState(() {}));
  }

  /// The sandbox demo endpoints make a TEST-mode session (or subscription) and return its token.
  Future<String?> demoToken(String path, String field) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
    try {
      final response = await (await client.postUrl(Uri.parse('$apiBase$path'))).close();
      final body = jsonDecode(await response.transform(utf8.decoder).join()) as Map<String, dynamic>;
      final value = (body['data'] as Map<String, dynamic>?)?[field] as String?;
      return value == null || value.isEmpty ? null : value;
    } catch (_) {
      return null;
    } finally {
      client.close();
    }
  }

  // `mor`: a session on the sandbox's merchant-of-record business (it has Cash App, Klarna and Amazon Pay on).
  Future<void> newSession([String? track]) async {
    setState(() => status = 'Creating a sandbox session…');
    final created = await demoToken('/demo/mobile-session${track == null ? '' : '?track=$track'}', 'clientToken');
    setState(() {
      if (created != null) token.text = created;
      status = created == null ? 'Could not reach the demo endpoint.' : 'Session ready.';
    });
  }

  Future<void> pay() async {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      result = null;
      status = 'Loading checkout…';
    });
    await checkout(() => vezmo.prepare(clientToken: token.text));
  }

  // A real app's server creates the subscription (POST /merchant/subscription) and returns its checkoutToken.
  Future<void> subscribe(String query) async {
    setState(() {
      result = null;
      status = 'Loading checkout…';
    });
    final subscriptionToken = await demoToken('/demo/mobile-subscription$query', 'subscriptionToken');
    if (subscriptionToken == null) {
      setState(() => status = 'Could not reach the demo endpoint.');
      return;
    }
    await checkout(() => vezmo.prepare(subscriptionToken: subscriptionToken));
  }

  Future<void> checkout(Future<VezmoError?> Function() prepare) async {
    final error = await prepare();
    if (error != null) {
      setState(() => status = 'Could not load: ${error.message}');
      return;
    }
    setState(() => status = '');
    final done = await vezmo.present();
    setState(() => result = done);
  }

  @override
  Widget build(BuildContext context) {
    final ready = token.text.trim().isNotEmpty;
    return Scaffold(
      backgroundColor: const Color(0xFFF4F5F8),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
          children: [
            const Text(
              'Northwind Audio',
              style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700, letterSpacing: -0.5, color: ink),
            ),
            const SizedBox(height: 16),
            card([
              Container(
                height: 150,
                decoration: BoxDecoration(color: lilac, borderRadius: BorderRadius.circular(14)),
                alignment: Alignment.center,
                child: const CustomPaint(size: Size(130, 102), painter: Headphones()),
              ),
              details('Aurora wireless headphones', r'$49.00'),
              primary('pay', 'Buy now', ready ? pay : null),
            ]),
            if (status.isNotEmpty) ...[
              const SizedBox(height: 16),
              Semantics(
                identifier: 'status',
                container: true,
                child: Text(
                  status,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: grey, fontSize: 14),
                ),
              ),
            ],
            if (result != null) ...[
              const SizedBox(height: 16),
              Semantics(
                identifier: 'result',
                container: true,
                child: Text(
                  'Result: ${jsonEncode(result!.toJson())}',
                  style: const TextStyle(color: ink, fontSize: 13, fontFamily: 'monospace'),
                ),
              ),
            ],
            const SizedBox(height: 16),
            CustomPaint(
              painter: const DashedBorder(),
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'SANDBOX · SDK ${VezmoCheckout.version}',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF8A8FA0),
                        letterSpacing: 0.4,
                      ),
                    ),
                    const SizedBox(height: 10),
                    Semantics(
                      identifier: 'client-token',
                      container: true,
                      child: TextField(
                        controller: token,
                        autocorrect: false,
                        enableSuggestions: false,
                        // Tapping outside the field closes the keyboard.
                        onTapOutside: (_) => FocusManager.instance.primaryFocus?.unfocus(),
                        style: const TextStyle(fontSize: 14, color: ink),
                        decoration: InputDecoration(
                          hintText: 'Client token from your server',
                          hintStyle: const TextStyle(color: Color(0xFFA1A1AA)),
                          filled: true,
                          fillColor: Colors.white,
                          isDense: true,
                          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
                          enabledBorder: field(),
                          focusedBorder: field(),
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    secondary('new-session', 'New sandbox session', () => newSession()),
                    const SizedBox(height: 10),
                    secondary('new-session-mor', 'New session · merchant of record', () => newSession('mor')),
                    const SizedBox(height: 10),
                    secondary(
                      'subscribe-now',
                      'Subscribe, no trial · merchant of record',
                      () => subscribe('?track=mor'),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            // A subscription with a free trial (the demo business's Club plan).
            card([
              Row(
                children: [
                  Container(
                    width: 56,
                    height: 56,
                    decoration: BoxDecoration(color: lilac, borderRadius: BorderRadius.circular(14)),
                    alignment: Alignment.center,
                    child: Container(
                      width: 26,
                      height: 26,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(color: dark, width: 7),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(child: details('Northwind Audio Club', r'$9.99 / month · first 7 days free')),
                ],
              ),
              primary('subscribe', 'Try 7 days free', () => subscribe('?trial=1')),
            ]),
          ],
        ),
      ),
    );
  }
}

OutlineInputBorder field() => OutlineInputBorder(
  borderRadius: BorderRadius.circular(12),
  borderSide: const BorderSide(color: line),
);

/// A white card on the page, as the React Native example's products.
Widget card(List<Widget> children) => Container(
  padding: const EdgeInsets.all(14),
  decoration: BoxDecoration(
    color: Colors.white,
    borderRadius: BorderRadius.circular(22),
    boxShadow: const [BoxShadow(color: Color(0x0F101840), blurRadius: 2, offset: Offset(0, 1))],
  ),
  child: Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (final (i, child) in children.indexed) ...[if (i > 0) const SizedBox(height: 14), child],
    ],
  ),
);

Widget details(String name, String price) => Padding(
  padding: const EdgeInsets.symmetric(horizontal: 4),
  child: Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        name,
        style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: ink),
      ),
      const SizedBox(height: 4),
      Text(price, style: const TextStyle(fontSize: 15, color: grey)),
    ],
  ),
);

/// A button Maestro finds by its id (`Semantics.identifier` is the Android resource id).
Widget button(String id, String label, VoidCallback? onTap, {required bool primary}) => Semantics(
  identifier: id,
  container: true,
  button: true,
  child: Opacity(
    opacity: onTap == null ? 0.35 : 1,
    child: Material(
      color: primary ? ink : Colors.white,
      shape: StadiumBorder(side: primary ? BorderSide.none : const BorderSide(color: line)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: primary ? 52 : 44,
          child: Center(
            child: Text(
              label,
              style: TextStyle(
                color: primary ? Colors.white : ink,
                fontSize: primary ? 16 : 14,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      ),
    ),
  ),
);

Widget primary(String id, String label, VoidCallback? onTap) => button(id, label, onTap, primary: true);

Widget secondary(String id, String label, VoidCallback onTap) => button(id, label, onTap, primary: false);

/// A pair of headphones, drawn: the band and two cups.
class Headphones extends CustomPainter {
  const Headphones();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = dark;
    // The band: 112 wide, 70 tall, a 9-wide stroke with a round top.
    const w = 9.0, left = 9.0, top = 0.0, width = 112.0, height = 70.0;
    const r = 56 - w / 2;
    final path = Path()
      ..moveTo(left + w / 2, top + height)
      ..lineTo(left + w / 2, top + 56)
      ..arcTo(Rect.fromCircle(center: const Offset(left + width / 2, top + 56), radius: r), pi, pi, false)
      ..lineTo(left + width - w / 2, top + height);
    canvas.drawPath(
      path,
      paint
        ..style = PaintingStyle.stroke
        ..strokeWidth = w,
    );
    // The cups overlap the band by 16; 74 apart.
    paint.style = PaintingStyle.fill;
    const cupTop = height - 16;
    for (final x in [(size.width - 74) / 2 - 28, (size.width + 74) / 2]) {
      canvas.drawRRect(RRect.fromLTRBR(x, cupTop, x + 28, cupTop + 48, const Radius.circular(12)), paint);
    }
  }

  @override
  bool shouldRepaint(Headphones oldDelegate) => false;
}

/// The sandbox box's dashed outline.
class DashedBorder extends CustomPainter {
  const DashedBorder();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = line
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    final outline = Path()
      ..addRRect(RRect.fromRectAndRadius((Offset.zero & size).deflate(0.5), const Radius.circular(16)));
    for (final metric in outline.computeMetrics()) {
      for (var d = 0.0; d < metric.length; d += 7) {
        canvas.drawPath(metric.extractPath(d, d + 4), paint);
      }
    }
  }

  @override
  bool shouldRepaint(DashedBorder oldDelegate) => false;
}
