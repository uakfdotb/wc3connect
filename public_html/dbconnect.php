<?php

try {
	$database = new PDO('mysql:host=localhost;dbname=ghost', 'dbuser', 'dbpassword', array(PDO::ATTR_EMULATE_PREPARES => false, PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION));
} catch(PDOException $ex) {
	die("Encountered database error.");
}

function databaseQuery($command, $array = array(), $assoc = false) {
	global $database;
	
	if(!is_array($array)) {
		die("Encountered database error.");
	}
	
	try {
		$query = $database->prepare($command);
		
		if(!$query) {
			die("Encountered database error.");
		}
		
		//set fetch mode depending on parameter
		if($assoc) {
			$query->setFetchMode(PDO::FETCH_ASSOC);
		} else {
			$query->setFetchMode(PDO::FETCH_NUM);
		}
		
		$success = $query->execute($array);
		
		if(!$success) {
			die("Encountered database error.");
		}
		
		return $query;
	} catch(PDOException $ex) {
		die("Encountered database error.");
	}
}

?>
