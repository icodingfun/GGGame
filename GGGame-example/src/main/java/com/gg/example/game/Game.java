package com.gg.example.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gg.core.harbor.HarborFutureTask;
import com.gg.core.harbor.HarborRPC;
import com.gg.example.common.ExampleConst;
import com.gg.example.protocol.user.IUserService;
import com.gg.example.protocol.user.User;

@Component
public class Game {

	private static final Logger logger = LoggerFactory.getLogger(Game.class);
	
	public void usertest() {
		IUserService us = HarborRPC.getHarbor(ExampleConst.UserService, IUserService.class);
		User u = us.getUserById("gametestid");
		logger.info(">>>>>>>>>>>>>>>>>>>: " + u.toString());
	}
	
	public void usertestasync() {
		IUserService us = HarborRPC.getHarbor(ExampleConst.UserService, IUserService.class);
		HarborFutureTask future = us.getUserByAge(30);
		future.addCallback((u) -> {
			logger.info("<<<<<<<<<<<<<<<<<: " + u.toString());
		});
	}
}