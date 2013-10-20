<?php
include('dbconnect.php');

$result = databaseQuery("SELECT data FROM wc3connect_list");

while($row = $result->fetch()) {
	echo $row[0] . "\n";
}
?>
