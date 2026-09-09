package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.payment.application.required.MemberFinder;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberFinderAdapter implements MemberFinder {

	private final MemberService memberService;

	@Override
	public Member getMemberByMemberNo(String memberNo) {
		return memberService.getMemberByMemberNo(memberNo);
	}
}
