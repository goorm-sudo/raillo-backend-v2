package com.sudo.raillo.payment.application.required;

import com.sudo.raillo.member.domain.Member;

/**
 * Member 도메인 접점 required port.
 */
public interface MemberFinder {

	Member getMemberByMemberNo(String memberNo);
}
