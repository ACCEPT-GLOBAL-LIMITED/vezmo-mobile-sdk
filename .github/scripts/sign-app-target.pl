#!/usr/bin/perl
# Distribution signing on the app target's build settings only (the ones naming its bundle id), so the CocoaPods
# targets keep theirs. Usage: perl sign-app-target.pl <project.pbxproj> <bundleId> <teamId> <profileName>
use strict;
use warnings;

my ($file, $bundle, $team, $profile) = @ARGV;
local $/;
open(my $in, '<', $file) or die "open $file: $!";
my $text = <$in>;
close $in;

my $count = 0;
$text =~ s{(buildSettings = \{)(.*?)(\n\t+\};)}{
  my ($open, $body, $close) = ($1, $2, $3);
  if ($body =~ /PRODUCT_BUNDLE_IDENTIFIER = "?\Q$bundle\E"?;/) {
    $body =~ s/\n\s*(?:CODE_SIGN_STYLE|DEVELOPMENT_TEAM|PROVISIONING_PROFILE_SPECIFIER|"?CODE_SIGN_IDENTITY(?:\[sdk=iphoneos\*\])?"?) = [^;]*;//g;
    $body .= "\n\t\t\t\tCODE_SIGN_STYLE = Manual;"
           . "\n\t\t\t\tDEVELOPMENT_TEAM = $team;"
           . "\n\t\t\t\tCODE_SIGN_IDENTITY = \"Apple Distribution\";"
           . "\n\t\t\t\t\"CODE_SIGN_IDENTITY[sdk=iphoneos*]\" = \"Apple Distribution\";"
           . "\n\t\t\t\tPROVISIONING_PROFILE_SPECIFIER = \"$profile\";";
    $count++;
  }
  "$open$body$close"
}gse;

die "No build settings for $bundle in $file\n" unless $count;
open(my $out, '>', $file) or die "write $file: $!";
print $out $text;
close $out;
print "Signing set on $count build configuration(s) of $bundle.\n";
