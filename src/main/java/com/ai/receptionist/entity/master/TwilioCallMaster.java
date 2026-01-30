package com.ai.receptionist.entity.master;

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
public class TwilioCallMaster implements Serializable{
	
	public String id;
	
	public long phoneNumber;

}
