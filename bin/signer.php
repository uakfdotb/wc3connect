<?php

echo "This tool will sign WC3Connect.jar to create SWC3Connect.jar.\n";
$passphrase = readline("Keystore passphrase: ");

$output = shell_exec("jarsigner -keystore keystore -storepass " . escapeshellarg($passphrase) . " -keypass " . escapeshellarg($passphrase) . " -signedjar SWC3Connect.jar WC3Connect.jar wc3connect");

echo $output;

?>