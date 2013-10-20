<?php

$ip = $_SERVER['REMOTE_ADDR'];
$postdata = file_get_contents("php://input");

if($postdata) {
	$headers = "From: noreply@yourdomain.com\r\n";
	$headers .= "To: you@yourdomain.com\r\n";
	$headers .= "Content-type: text/plain\r\n";

	mail("you@yourdomain.com", "WC3Connect log received $ip", "Log is reproduced below.\n\n" . $postdata, $headers);
}

?>
