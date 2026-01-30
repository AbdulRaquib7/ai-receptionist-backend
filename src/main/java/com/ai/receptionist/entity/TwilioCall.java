package com.ai.receptionist.entity;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TwilioCall implements Serializable{
	
	public String id;
	
	public String userId;
	
	public String userResponse;
	
	public String aiResponse;

}
