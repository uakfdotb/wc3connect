<?php

echo "This tool generates a keystore that you can use to sign your applet. Note that it will be a self-signed certificate.\n";
$passphrase = readline("Keystore passphrase: ");
$commonname = readline("Common name: ");
$orgname = readline("Organization name: ");
$location = readline("Physical location: ");
$state = readline("State/province: ");
$country = readline("Country: ");
$time = readline("Days to sign for: ");

$output = shell_exec("keytool -genkey -alias wc3connect -keystore keystore -keypass " . escapeshellarg($passphrase) . " -dname " . escapeshellarg("cn=$commonname, OU=$orgname, O=$orgname, L=$location, S=$state, C=$country") . " -storepass " . escapeshellarg($passphrase) . " -validity " . escapeshellarg($time));

echo $output;

?>