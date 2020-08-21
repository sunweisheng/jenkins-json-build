//
//  PresenterTest.m
//  CICD-ObjectC-UnitTests
//
//  Created by Test on 2020/8/17.
//  Copyright © 2020 Test. All rights reserved.
//

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>
#import <OCHamcrest/OCHamcrest.h>
#import "TestViewController.h"
#import "TestPresenter.h"
#import "TestProtocol.h"

@interface TestPresenterTest : XCTestCase
{
    id _delegateMock;
    TestPresenter *_presenter;
}
@end

@implementation TestPresenterTest

- (void)setUp {
    // Put setup code here. This method is called before the invocation of each test method in the class.
    _delegateMock   = OCMProtocolMock(@protocol(TestProtocol));
    _presenter      = [[TestPresenter alloc] initWithDelegate:_delegateMock];
}

- (void)tearDown {
    // Put teardown code here. This method is called after the invocation of each test method in the class.
}

/**
 * 方法名：personClickTestButton
 * 条件：    点击Test 按钮
 * 结果：    协议方法 protocolShowAlertMsg: 被执行
 * 编写：    ShenYj
 */
- (void)testPersonClickTestButton {
    OCMStub([_delegateMock protocolShowAlertMsg:[OCMArg any]]);
    [_presenter personClickTestButton];
    OCMVerify([_delegateMock protocolShowAlertMsg:[OCMArg any]]);
}
/**
 * 方法名：personClickCloseButton
 * 条件：    在Alert 弹层中, 点击了关闭按钮
 * 结果：    协议方法 protocolClickCloseButton 被执行
 * 编写：    ShenYj
 */
- (void)testPersonClickCloseButton {
    OCMStub([_delegateMock protocolClickCloseButton]);
    [_presenter personClickCloseButton];
    OCMVerify([_delegateMock protocolClickCloseButton]);
}
/**
 * 方法名：personClickConfirmButton
 * 条件：    在Alert 弹层中, 点击了确定按钮
 * 结果：    协议方法 protocolClickConfirmButton 被执行
 * 编写：    ShenYj
 */
- (void)testPersonClickConfirmButton {
    OCMStub([_delegateMock protocolClickConfirmButton]);
    [_presenter personClickConfirmButton];
    OCMVerify([_delegateMock protocolClickConfirmButton]);
}

- (void)testPerformanceExample {
    // This is an example of a performance test case.
    [self measureBlock:^{
        // Put the code you want to measure the time of here.
    }];
}

@end
