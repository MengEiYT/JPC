package com.jpc.vm;

public class VmException extends Exception {
	public VmException(String message) {
		super(message);
	}

	public VmException(String message, Throwable cause) {
		super(message, cause);
	}
}
